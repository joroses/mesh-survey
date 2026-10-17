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

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurveyUploaderTest {
    private val dao = FakeSurveyDao()
    private val settings =
        object : SurveySettings {
            override val settings =
                MutableStateFlow(SurveyUploadSettings(url = "https://maps.example/", user = "u", password = "p"))

            /** Set to make every read fail, as an unreadable settings file does. */
            var readError: IOException? = null

            override suspend fun current(): SurveyUploadSettings = readError?.let { throw it } ?: super.current()

            override suspend fun saveServer(url: String, user: String, password: String) = Unit

            override suspend fun setAutomatic(automatic: Boolean) = Unit
        }
    private val clock =
        object : SurveyClock {
            override fun wallTimeMs() = 1_000L

            override fun elapsedRealtimeMs() = 0L
        }

    /** Bodies the server received, and what it answers: it saves every batch unless [fail] is set. */
    private val received = mutableListOf<JsonObject>()
    private var fail = false
    private var authorization: String? = null

    /** Rows the server skips in every batch, by reason. */
    private var skipPerBatch = emptyMap<String, Int>()

    private fun TestScope.uploader(): SurveyUploader {
        val engine = MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            if (fail) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
            val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            received += body
            val rows = body.getValue("measurements").jsonArray
            val lastSeq = rows.last().jsonObject.getValue("seq").jsonPrimitive.long
            val skipped = skipPerBatch.entries.joinToString { (reason, count) -> "\"$reason\": $count" }
            respond(
                """{"saved": ${rows.size - skipPerBatch.values.sum()}, "already_saved": 0, """ +
                    """"skipped": {$skipped}, "last_seq": $lastSeq}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return SurveyUploader(
            dao,
            settings,
            HttpClient(engine),
            clock,
            object : ApplicationCoroutineScope, CoroutineScope by backgroundScope {},
        )
    }

    private suspend fun record(sessionId: String, count: Int) {
        if (dao.sessions.none { it.id == sessionId }) {
            dao.insertSession(
                SurveySession(
                    id = sessionId,
                    startedAt = dao.sessions.size.toLong(),
                    receiverNodeId = 1,
                    receiverName = "Survey node",
                    modemPreset = "LONG_FAST",
                    spreadingFactor = 11,
                    bandwidthKhz = 250f,
                    frequencyMhz = 869.525f,
                    appVersion = "test",
                ),
            )
        }
        repeat(count) {
            dao.insertMeasurement(
                SurveyMeasurement(
                    sessionId = sessionId,
                    heardAtMs = 1,
                    latitude = 41.9,
                    longitude = 1.57,
                    accuracyM = 5f,
                    fixAgeMs = 300,
                    senderNodeId = 2,
                    recipientNodeId = 4_294_967_295,
                    packetId = 3,
                    rxSnr = 0f,
                    rxRssi = null,
                    hopStart = 3,
                    hopLimit = 1,
                    relayByte = 0x1a,
                    okToMqtt = false,
                ),
            )
        }
    }

    @Test
    fun `batches advance uploaded_seq, and a rerun sends nothing twice`() = runTest {
        record("a", SurveyUploader.BATCH_SIZE + 1)
        record("b", 2)
        val uploader = uploader()

        uploader.upload()
        uploader.upload()

        assertEquals(listOf(500, 1, 2), received.map { it.getValue("measurements").jsonArray.size })
        assertEquals(
            listOf("a", "a", "b"),
            received.map { it.getValue("session").jsonObject["id"]!!.jsonPrimitive.content },
        )
        assertEquals(listOf(501L, 503L), dao.sessions.map { it.uploadedSeq })
        assertEquals("Basic dTpw", authorization)
    }

    @Test
    fun `a password without a user name is still sent, and none without either`() = runTest {
        record("a", 1)
        settings.settings.value = SurveyUploadSettings(url = "https://maps.example/", password = "p")
        val uploader = uploader()

        uploader.upload()
        assertEquals("Basic OnA=", authorization)

        record("a", 1)
        settings.settings.value = SurveyUploadSettings(url = "https://maps.example/")
        uploader.upload()
        assertNull(authorization)
    }

    @Test
    fun `unreadable settings skip the run instead of throwing`() = runTest {
        record("a", 1)
        settings.readError = IOException("disk")
        val uploader = uploader()

        uploader.uploadIfAutomatic()
        uploader.upload()

        assertTrue(received.isEmpty())
        assertEquals(0L, dao.sessions.single().uploadedSeq)
    }

    @Test
    fun `missing readings are sent as null, a zero SNR as zero`() = runTest {
        record("a", 1)

        uploader().upload()

        val row = received.single().getValue("measurements").jsonArray.single().jsonObject
        assertEquals("null", row.getValue("rx_rssi").toString())
        assertEquals("0.0", row.getValue("rx_snr").toString())
        assertEquals(26, row.getValue("relay_byte").jsonPrimitive.content.toInt())
        assertEquals("false", row.getValue("ok_to_mqtt").toString())
        assertEquals("null", row.getValue("default_key").toString())
    }

    @Test
    fun `the session sends its bandwidth and frequency`() = runTest {
        record("a", 1)

        uploader().upload()

        val session = received.single().getValue("session").jsonObject
        assertEquals("250.0", session.getValue("bandwidth_khz").toString())
        assertEquals("869.525", session.getValue("frequency_mhz").toString())
    }

    @Test
    fun `what the server kept and skipped adds up across batches`() = runTest {
        record("a", SurveyUploader.BATCH_SIZE + 3)
        skipPerBatch = mapOf("no_position" to 2, "too_old" to 1)

        uploader().upload()

        val session = dao.sessions.single()
        assertEquals(SurveyUploader.BATCH_SIZE + 3 - 6L, session.serverKept)
        assertEquals(6L, session.serverSkipped)
        assertEquals(
            mapOf("no_position" to 4L, "too_old" to 2L),
            SurveyUploader.decodeSkipReasons(session.serverSkipReasons),
        )
    }

    @Test
    fun `a zero-hop packet heard from its sender keeps its hop counts`() = runTest {
        record("a", 1)
        // Sender 2's own relay byte, as on a Range Test packet heard straight from its sender.
        dao.measurements.replaceAll { it.copy(hopStart = 0, hopLimit = 0, relayByte = 2) }

        uploader().upload()

        val row = received.single().getValue("measurements").jsonArray.single().jsonObject
        assertEquals("0", row.getValue("hop_start").toString())
        assertEquals("0", row.getValue("hop_limit").toString())
        assertEquals("2", row.getValue("relay_byte").toString())
    }

    @Test
    fun `a hop_start of 0 from old firmware is sent raw for SigSleuth to judge`() = runTest {
        record("a", 1)
        dao.measurements.replaceAll { it.copy(hopStart = 0, hopLimit = 3, relayByte = null) }

        uploader().upload()

        val row = received.single().getValue("measurements").jsonArray.single().jsonObject
        assertEquals("0", row.getValue("hop_start").toString())
        assertEquals("3", row.getValue("hop_limit").toString())
        assertEquals("null", row.getValue("relay_byte").toString())
    }

    @Test
    fun `a URL without a scheme or host is never posted`() = runTest {
        record("a", 1)
        val uploader = uploader()
        for (
        url in
        listOf("sigsleuth.example.org", "sigsleuth.example.org/api", "localhost:8001", "ftp://x.org", "https://")
        ) {
            settings.settings.value = SurveyUploadSettings(url = url, password = "p")
            uploader.upload()
        }

        assertEquals(emptyList(), received)
        assertNull(authorization)
        assertEquals(0L, dao.sessions.single().uploadedSeq)
        assertTrue(dao.sessions.single().lastUploadError!!.contains("https://"))
        assertTrue(isUploadUrl("http://localhost:8001") && isUploadUrl("HTTPS://sigsleuth.example.org/"))
    }

    @Test
    fun `plain http is accepted only for this device, the one place cleartext may go`() {
        assertTrue(isUploadUrl("http://127.0.0.1:8001") && isUploadUrl("HTTP://LOCALHOST/"))
        assertFalse(isUploadUrl("http://192.168.1.10:8000"))
        assertFalse(isUploadUrl("http://sigsleuth.example.org"))
        assertFalse(isUploadUrl("ftp://sigsleuth.example.org"))
    }

    @Test
    fun `a failure keeps uploaded_seq, saves the error, and the next run resumes`() = runTest {
        record("a", 3)
        val uploader = uploader()
        fail = true

        uploader.upload()

        assertEquals(0L, dao.sessions.single().uploadedSeq)
        assertEquals("HTTP 503", dao.sessions.single().lastUploadError)

        fail = false
        uploader.upload()

        assertEquals(3L, dao.sessions.single().uploadedSeq)
        assertNull(dao.sessions.single().lastUploadError)
    }
}
