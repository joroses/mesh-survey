/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.feature.survey

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.safeCatching
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

/**
 * Uploads every measurement SigSleuth does not have yet, oldest session first, in batches of [BATCH_SIZE]. Each batch
 * is the rows after the session's `uploaded_seq`; the `last_seq` the server returns becomes the new `uploaded_seq`. The
 * server ignores rows it already holds, so a repeated batch saves nothing twice.
 *
 * A run stops at the first failure and saves its error; the next run tries again. Two runs never overlap.
 */
@Single
class SurveyUploader(
    private val dao: SurveyDao,
    private val settings: SurveySettings,
    private val http: HttpClient,
    private val clock: SurveyClock,
    private val scope: ApplicationCoroutineScope,
) {
    private val runs = Mutex()
    private val mutableUploading = MutableStateFlow(false)
    val uploading: StateFlow<Boolean> = mutableUploading.asStateFlow()

    fun uploadNow() {
        scope.launch { upload() }
    }

    /** The service calls this every [RECORDING_INTERVAL] while recording and once when the session ends. */
    suspend fun uploadIfAutomatic() {
        // An unreadable settings file skips this run; letting it throw would crash the app from the service's scope.
        if (safeCatching { settings.current() }.getOrNull()?.automatic == true) upload()
    }

    suspend fun upload() = runs.withLock {
        val target = safeCatching { settings.current() }.getOrNull() ?: return@withLock
        if (target.url.isBlank()) return@withLock
        mutableUploading.value = true
        try {
            // A database error ends the run like a network error; the next run tries again.
            val sessions = safeCatching { dao.sessionsToUpload() }.getOrDefault(emptyList())
            if (!isUploadUrl(target.url)) {
                sessions.firstOrNull()?.let { safeCatching { dao.uploadFailed(it.session.id, INVALID_URL) } }
                return@withLock
            }
            for (pending in sessions) {
                if (!safeCatching { uploadSession(pending.session, target) }.getOrDefault(false)) break
            }
        } finally {
            mutableUploading.value = false
        }
    }

    /** Returns false at the first failure, after saving its error on the session. */
    @Suppress("ReturnCount")
    private suspend fun uploadSession(session: SurveySession, target: SurveyUploadSettings): Boolean {
        var afterSeq = session.uploadedSeq
        var kept = session.serverKept
        var skipped = session.serverSkipped
        val skipReasons = decodeSkipReasons(session.serverSkipReasons).toMutableMap()
        while (true) {
            val rows = dao.measurementsAfter(session.id, afterSeq, BATCH_SIZE)
            if (rows.isEmpty()) return true
            val ack = post(target, UploadRequest(session.toUpload(), rows.map { it.toUpload() }))
            val response = ack.getOrElse { error ->
                dao.uploadFailed(
                    session.id,
                    (error.message ?: error::class.simpleName.orEmpty()).take(MAX_ERROR_LENGTH),
                )
                Logger.w { "Signal survey upload failed: ${error.message}" }
                return false
            }
            // Counted only when the phone records the batch, so a batch sent again is not counted twice.
            kept += response.saved + response.alreadySaved
            skipped += response.skipped.values.sum()
            response.skipped.forEach { (reason, count) ->
                skipReasons[reason] = skipReasons.getOrElse(reason) { 0 } + count
            }
            val lastSeq = response.lastSeq
            dao.uploaded(
                session.id,
                lastSeq,
                clock.wallTimeMs(),
                kept,
                skipped,
                skipReasons.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) },
            )
            // A server that acknowledges less than it was sent would otherwise get the same batch forever.
            if (lastSeq < rows.last().seq) {
                dao.uploadFailed(session.id, "Server kept $lastSeq of ${rows.last().seq}")
                return false
            }
            afterSeq = lastSeq
        }
    }

    private suspend fun post(target: SurveyUploadSettings, request: UploadRequest): Result<UploadResponse> =
        safeCatching {
            val response =
                http.post(target.url.trimEnd('/') + UPLOAD_PATH) {
                    // SigSleuth ignores the user name, so a password alone must still be sent.
                    if (target.user.isNotBlank() || target.password.isNotEmpty()) {
                        val credentials = Base64.encode("${target.user}:${target.password}".encodeToByteArray())
                        header(HttpHeaders.Authorization, "Basic $credentials")
                    }
                    setBody(TextContent(json.encodeToString(request), ContentType.Application.Json))
                }
            if (!response.status.isSuccess()) throw UploadException("HTTP ${response.status.value}")
            json.decodeFromString<UploadResponse>(response.bodyAsText())
        }

    private class UploadException(message: String) : Exception(message)

    companion object {
        const val UPLOAD_PATH = "/api/v1/coverage/uploads"
        const val BATCH_SIZE = 500
        private const val MAX_ERROR_LENGTH = 120
        private const val INVALID_URL = "The SigSleuth URL must start with https://"
        val RECORDING_INTERVAL = 60.seconds

        /** A session's saved skip reasons; an unreadable value counts as none. */
        fun decodeSkipReasons(text: String?): Map<String, Long> =
            text?.let { safeCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty()

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = true
        }
    }
}

@Serializable internal data class UploadRequest(val session: UploadSession, val measurements: List<UploadMeasurement>)

@Serializable
internal data class UploadSession(
    val id: String,
    @SerialName("receiver_node_id") val receiverNodeId: Long,
    @SerialName("receiver_name") val receiverName: String,
    @SerialName("modem_preset") val modemPreset: String?,
    @SerialName("spreading_factor") val spreadingFactor: Int,
    @SerialName("bandwidth_khz") val bandwidthKhz: Float,
    @SerialName("frequency_mhz") val frequencyMhz: Float?,
    @SerialName("app_version") val appVersion: String,
)

@Serializable
internal data class UploadMeasurement(
    val seq: Long,
    @SerialName("heard_at_ms") val heardAtMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("accuracy_m") val accuracyM: Float?,
    @SerialName("fix_age_ms") val fixAgeMs: Long?,
    @SerialName("sender_node_id") val senderNodeId: Long,
    @SerialName("recipient_node_id") val recipientNodeId: Long,
    @SerialName("packet_id") val packetId: Long,
    @SerialName("rx_snr") val rxSnr: Float?,
    @SerialName("rx_rssi") val rxRssi: Int?,
    @SerialName("hop_start") val hopStart: Int,
    @SerialName("hop_limit") val hopLimit: Int,
    @SerialName("relay_byte") val relayByte: Int?,
    @SerialName("ok_to_mqtt") val okToMqtt: Boolean?,
    @SerialName("default_key") val defaultKey: Boolean?,
)

/** SigSleuth's answer: [lastSeq] is the batch's highest seq, saved or not; [skipped] counts unusable rows by reason. */
@Serializable
internal data class UploadResponse(
    val saved: Long = 0,
    @SerialName("already_saved") val alreadySaved: Long = 0,
    val skipped: Map<String, Long> = emptyMap(),
    @SerialName("last_seq") val lastSeq: Long,
)

private fun SurveySession.toUpload() = UploadSession(
    id,
    receiverNodeId,
    receiverName,
    modemPreset,
    spreadingFactor,
    bandwidthKhz,
    frequencyMhz,
    appVersion,
)

private fun SurveyMeasurement.toUpload() = UploadMeasurement(
    seq = seq,
    heardAtMs = heardAtMs,
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracyM,
    fixAgeMs = fixAgeMs,
    senderNodeId = senderNodeId,
    recipientNodeId = recipientNodeId,
    packetId = packetId,
    rxSnr = rxSnr,
    rxRssi = rxRssi,
    // Raw: a hop start of 0 is either firmware older than hop counts or a zero-hop packet, and SigSleuth tells them
    // apart with the relay byte (known_hop_start).
    hopStart = hopStart,
    hopLimit = hopLimit,
    relayByte = relayByte,
    okToMqtt = okToMqtt,
    defaultKey = defaultKey,
)
