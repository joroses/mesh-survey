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
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SurveyRelayNodesTest {
    private val directory = createTempDirectory("relay-nodes").toFile()
    private val settings =
        object : SurveySettings {
            override val settings =
                MutableStateFlow(SurveyUploadSettings(url = "https://maps.example/", user = "u", password = "p"))

            override suspend fun saveServer(url: String, user: String, password: String) = false

            override suspend fun setAutomatic(automatic: Boolean) = Unit

            override val keepScreenOn = MutableStateFlow(false)

            override suspend fun setKeepScreenOn(keepScreenOn: Boolean) = Unit
        }
    private var now = DIRECT_HISTORY.inWholeMilliseconds
    private val clock =
        object : SurveyClock {
            override fun wallTimeMs() = now

            override fun elapsedRealtimeMs() = 0L
        }
    private val requests = mutableListOf<HttpRequestData>()
    private var fail = false
    private val relayNodes =
        FileSurveyRelayNodes(
            directory,
            settings,
            HttpClient(
                MockEngine { request ->
                    requests += request
                    if (fail) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
                    respond(
                        """{"nodes": [{"node_id": 319, "long_name": "Hill", "short_name": "", "latitude": 41.9, """ +
                            """"longitude": 1.57, "estimated": true, "reach_km": 91.5}], "truncated": false}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            clock,
        )

    @AfterTest
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun `a fetched list is saved for its server and spreading factor, and fetched again once stale`() = runTest {
        assertNull(relayNodes.saved(11))
        assertEquals(listOf(0x13FL), relayNodes.refresh(11)?.byByte?.get(0x3F)?.map { it.nodeId })
        assertEquals("spreading_factor=11", requests.single().url.encodedQuery)
        assertEquals("Basic dTpw", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(
            MappedNode(0x13F, "Hill", 41.9, 1.57, estimated = true, reachMeters = 91_500.0),
            relayNodes.saved(11)?.byByte?.get(0x3F)?.single(),
        )
        assertNull(relayNodes.saved(12))

        // Fresh, it is not fetched again; stale, a failed fetch keeps it.
        assertNull(relayNodes.refresh(11))
        now += RELAY_NODES_STALE.inWholeMilliseconds + 1
        fail = true
        assertFailsWith<IOException> { relayNodes.refresh(11) }
        assertEquals(2, requests.size)
        assertNotNull(relayNodes.saved(11))

        // Another server's list, or one older than the relay history, is not used.
        settings.settings.value = settings.settings.value.copy(url = "https://other.example/")
        assertNull(relayNodes.saved(11))
        settings.settings.value = settings.settings.value.copy(url = "https://maps.example/")
        now += DIRECT_HISTORY.inWholeMilliseconds
        assertNull(relayNodes.saved(11))
    }
}
