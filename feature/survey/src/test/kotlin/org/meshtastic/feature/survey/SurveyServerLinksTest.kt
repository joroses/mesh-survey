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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyServerLinksTest {
    private val directory = createTempDirectory("server-links").toFile()
    private var now = 0L
    private val clock =
        object : SurveyClock {
            override fun wallTimeMs() = now

            override fun elapsedRealtimeMs() = 0L
        }
    private val requests = mutableListOf<HttpRequestData>()
    private var fail = false
    private var telegram = "\"https://t.me/mesh\""
    private val links =
        FileSurveyServerLinks(
            directory,
            HttpClient(
                MockEngine { request ->
                    requests += request
                    if (fail) return@MockEngine respondError(HttpStatusCode.ServiceUnavailable)
                    respond(
                        """{"capture": null, "revision": null, "telegram_url": $telegram, "status": "ok"}""",
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

    private suspend fun telegram(server: String = "https://maps.example/") = links.telegram(server).toList()

    @Test
    fun `the link is saved for its server, kept offline and fetched again once stale`() = runTest {
        assertEquals(listOf(null, "https://t.me/mesh"), telegram())
        assertEquals("https://maps.example/api/v1/status", requests.single().url.toString())
        assertEquals(null, requests.single().headers[HttpHeaders.Authorization])

        // Fresh, it is not fetched again; stale, a failed fetch keeps it.
        assertEquals(listOf("https://t.me/mesh"), telegram())
        now += SERVER_LINKS_STALE.inWholeMilliseconds + 1
        fail = true
        assertEquals(listOf("https://t.me/mesh"), telegram())
        assertEquals(2, requests.size)

        // Another server's link is not shown, and a server without one shows none.
        fail = false
        telegram = "null"
        assertEquals(listOf(null, null), telegram("https://other.example"))
    }

    @Test
    fun `only an https link is kept, and only an upload server is asked`() = runTest {
        telegram = "\"tg://resolve?domain=mesh\""
        assertEquals(listOf(null, null), telegram())
        now += SERVER_LINKS_STALE.inWholeMilliseconds + 1
        telegram = "\"https://\""
        assertEquals(listOf(null, null), telegram())
        now += SERVER_LINKS_STALE.inWholeMilliseconds + 1
        telegram = "\" https://t.me/mesh \""
        assertEquals(listOf(null, "https://t.me/mesh"), telegram())
        assertEquals(listOf(null), telegram("maps.example"))
        assertEquals(listOf(null), telegram(""))
        assertEquals(3, requests.size)
    }

    @Test
    fun `node link points to the node's coverage view on an upload server`() {
        assertEquals(
            "https://maps.example/#nodes/!02eb82f4?show=coverage",
            sigSleuthNodeUrl("https://maps.example/", 0x02eb82f4L),
        )
        assertEquals(
            "https://maps.example/sub/#nodes/!0000002a?show=coverage",
            sigSleuthNodeUrl("https://maps.example/sub", 0x2aL),
        )
        assertEquals(
            "http://localhost:8000/#nodes/!deadbeef?show=coverage",
            sigSleuthNodeUrl("http://localhost:8000/", 0xdeadbeefL),
        )
        assertEquals(null, sigSleuthNodeUrl("http://maps.example", 0x02eb82f4L))
        assertEquals(null, sigSleuthNodeUrl("", 0x02eb82f4L))
        assertEquals(null, sigSleuthNodeUrl("https://maps.example", 0L))
    }
}
