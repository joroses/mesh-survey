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
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.common.util.safeCatching
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.days

/** How old a saved relay node list may get before a recording session fetches it again. */
val RELAY_NODES_STALE = 1.days

/**
 * SigSleuth's list of the nodes that could relay on a spreading factor, bandwidth and frequency, kept on the phone so
 * relay guesses can use it offline. All count: ShortFast and NarrowFast share SF7, and channels on one preset differ by
 * frequency. Only the list from the server set now counts.
 */
interface SurveyRelayNodes {
    /** The list saved for these radio settings, unless it is older than [DIRECT_HISTORY]. */
    suspend fun saved(spreadingFactor: Int, bandwidthKhz: Float, frequencyMhz: Float?): MappedNodes?

    /**
     * Fetches and saves the list for these radio settings unless the saved one is newer than [RELAY_NODES_STALE].
     * Returns the new list, or null when it fetched none; throws when the fetch fails.
     */
    suspend fun refresh(spreadingFactor: Int, bandwidthKhz: Float, frequencyMhz: Float?): MappedNodes?
}

/** Saves each radio settings' list in [directory] as JSON, with the server it came from and when. */
class FileSurveyRelayNodes(
    private val directory: File,
    private val settings: SurveySettings,
    private val http: HttpClient,
    private val clock: SurveyClock,
) : SurveyRelayNodes {
    override suspend fun saved(spreadingFactor: Int, bandwidthKhz: Float, frequencyMhz: Float?): MappedNodes? {
        val url = settings.current().url
        val oldest = clock.wallTimeMs() - DIRECT_HISTORY.inWholeMilliseconds
        return read(file(spreadingFactor, bandwidthKhz, frequencyMhz))
            ?.takeIf { it.server == url && it.fetchedAtMs >= oldest }
            ?.toMappedNodes()
    }

    override suspend fun refresh(spreadingFactor: Int, bandwidthKhz: Float, frequencyMhz: Float?): MappedNodes? {
        val target = settings.current()
        val now = clock.wallTimeMs()
        val file = file(spreadingFactor, bandwidthKhz, frequencyMhz)
        val saved = read(file)
        val fresh = saved?.server == target.url && saved.fetchedAtMs > now - RELAY_NODES_STALE.inWholeMilliseconds
        if (fresh || !isUploadUrl(target.url)) return null
        val response =
            http.get(target.url.trimEnd('/') + PATH) {
                parameter("spreading_factor", spreadingFactor)
                parameter("bandwidth_khz", bandwidthKhz)
                parameter("frequency_mhz", frequencyMhz)
                authorize(target)
            }
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
        val fetched =
            SavedRelayNodes(target.url, now, json.decodeFromString<RelayNodeList>(response.bodyAsText()).nodes)
        write(file, fetched)
        return fetched.toMappedNodes()
    }

    /** Null when missing or unreadable: the next refresh replaces it. */
    private suspend fun read(file: File): SavedRelayNodes? = withContext(ioDispatcher) {
        safeCatching { json.decodeFromString<SavedRelayNodes>(file.readText()) }.getOrNull()
    }

    private suspend fun write(target: File, nodes: SavedRelayNodes) = withContext(ioDispatcher) {
        directory.mkdirs()
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(json.encodeToString(nodes))
        // Replaced whole, so a crash never leaves half a list.
        if (!temporary.renameTo(target)) throw IOException("Could not save the relay node list")
    }

    private fun file(spreadingFactor: Int, bandwidthKhz: Float, frequencyMhz: Float?) =
        File(directory, "relay-nodes-sf$spreadingFactor-bw$bandwidthKhz${frequencyMhz?.let { "-f$it" }.orEmpty()}.json")

    companion object {
        const val PATH = "/api/v1/coverage/relay-nodes"
        private const val METERS_PER_KM = 1_000.0

        private val json = Json { ignoreUnknownKeys = true }

        private fun SavedRelayNodes.toMappedNodes() = MappedNodes(
            fetchedAtMs,
            nodes.map {
                MappedNode(
                    nodeId = it.nodeId,
                    shortName = it.shortName?.takeIf(String::isNotBlank) ?: it.longName?.takeIf(String::isNotBlank),
                    latitude = it.latitude,
                    longitude = it.longitude,
                    estimated = it.estimated,
                    reachMeters = it.reachKm?.let { km -> km * METERS_PER_KM },
                )
            },
        )
    }
}

@Serializable
internal data class RelayNodeJson(
    @SerialName("node_id") val nodeId: Long,
    @SerialName("long_name") val longName: String? = null,
    @SerialName("short_name") val shortName: String? = null,
    val latitude: Double,
    val longitude: Double,
    val estimated: Boolean = false,
    @SerialName("reach_km") val reachKm: Double? = null,
)

@Serializable internal data class RelayNodeList(val nodes: List<RelayNodeJson>)

@Serializable
internal data class SavedRelayNodes(
    val server: String,
    @SerialName("fetched_at_ms") val fetchedAtMs: Long,
    val nodes: List<RelayNodeJson>,
)
