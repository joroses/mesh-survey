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
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.common.util.safeCatching
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.days

/** How old the saved Telegram link may get before it is fetched again. */
val SERVER_LINKS_STALE = 1.days

/** The upload server's Telegram group, from its status, kept on the phone so the legend links to it offline. */
interface SurveyServerLinks {
    /** The link saved for [server], then the fetched one once the saved one is stale. Null when it gives none. */
    fun telegram(server: String): Flow<String?>
}

/** Saves the last server's link in [directory] as JSON, with the server it came from and when. */
class FileSurveyServerLinks(
    private val directory: File,
    private val http: HttpClient,
    private val clock: SurveyClock,
) : SurveyServerLinks {
    override fun telegram(server: String): Flow<String?> = flow {
        if (!isUploadUrl(server)) {
            emit(null)
            return@flow
        }
        val saved = read()?.takeIf { it.server == server }
        emit(saved?.telegramUrl)
        if (saved != null && saved.fetchedAtMs > clock.wallTimeMs() - SERVER_LINKS_STALE.inWholeMilliseconds) {
            return@flow
        }
        // Offline the saved link stays; the screen tries again when it next opens.
        val fetched =
            safeCatching { fetch(server) }
                .onFailure { Logger.w { "Signal survey server links not fetched: ${it::class.simpleName}" } }
                .getOrNull() ?: return@flow
        emit(fetched.telegramUrl)
    }

    private suspend fun fetch(server: String): SavedServerLinks {
        val response = http.get(server.trimEnd('/') + PATH)
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
        val telegram = json.decodeFromString<ServerStatus>(response.bodyAsText()).telegramUrl
        // Opened as given, so only a web link.
        val links = SavedServerLinks(server, clock.wallTimeMs(), telegram?.takeIf { it.startsWith("https://") })
        write(links)
        return links
    }

    /** Null when missing or unreadable: the next fetch replaces it. */
    private suspend fun read(): SavedServerLinks? = withContext(ioDispatcher) {
        safeCatching { json.decodeFromString<SavedServerLinks>(File(directory, FILE).readText()) }.getOrNull()
    }

    private suspend fun write(links: SavedServerLinks) = withContext(ioDispatcher) {
        directory.mkdirs()
        val temporary = File(directory, "$FILE.tmp")
        temporary.writeText(json.encodeToString(links))
        if (!temporary.renameTo(File(directory, FILE))) throw IOException("Could not save the server links")
    }

    companion object {
        const val PATH = "/api/v1/status"
        private const val FILE = "server-links.json"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable internal data class ServerStatus(@SerialName("telegram_url") val telegramUrl: String? = null)

@Serializable
internal data class SavedServerLinks(
    val server: String,
    @SerialName("fetched_at_ms") val fetchedAtMs: Long,
    @SerialName("telegram_url") val telegramUrl: String?,
)
