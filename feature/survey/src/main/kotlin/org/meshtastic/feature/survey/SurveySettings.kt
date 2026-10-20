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

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.safeCatching
import java.net.URI

/** Where to upload. A blank [url] means uploads are off. The password goes in a Basic header, never in the URL. */
data class SurveyUploadSettings(
    val url: String = "",
    val user: String = "",
    val password: String = "",
    val automatic: Boolean = true,
) {
    override fun toString(): String = "SurveyUploadSettings(url=$url, automatic=$automatic)"
}

/**
 * Whether [url] is an absolute https URL with a host, or an http one to this device. The shared HttpClient resolves
 * anything else against its default base URL, api.meshtastic.org, so "sigsleuth.example.org" would post the survey and
 * its password there. The app's network security config blocks cleartext except to localhost and 127.0.0.1, so any
 * other http URL would save but never upload.
 */
fun isUploadUrl(url: String): Boolean {
    val uri = safeCatching { URI(url.trim()) }.getOrNull()
    val host = uri?.host?.lowercase().orEmpty()
    return when (uri?.scheme?.lowercase()) {
        "https" -> host.isNotBlank()
        "http" -> host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1"
        else -> false
    }
}

interface SurveySettings {
    val settings: Flow<SurveyUploadSettings>

    suspend fun current(): SurveyUploadSettings = settings.first()

    /**
     * Saves where to upload; leaves [SurveyUploadSettings.automatic] alone. Returns whether the URL changed. Call
     * [SurveyUploader.saveServer] instead, which also starts the uploads over for a new URL.
     */
    suspend fun saveServer(url: String, user: String, password: String): Boolean

    /**
     * Saves only the switch, so a tap right after Save, before the screen has the saved values, cannot write the old
     * URL or password back.
     */
    suspend fun setAutomatic(automatic: Boolean)

    /** Whether the survey screen keeps the display on while recording, as in a car. Off unless the user turns it on. */
    val keepScreenOn: Flow<Boolean>

    suspend fun setKeepScreenOn(keepScreenOn: Boolean)
}

@Single
class DataStoreSurveySettings(context: Application) : SurveySettings {
    // A corrupt file would otherwise throw out of every read, including the service's automatic upload.
    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler(produceNewData = { emptyPreferences() }),
        ) {
            context.preferencesDataStoreFile("signal_survey_ds")
        }

    override val settings: Flow<SurveyUploadSettings> =
        store.data.map {
            SurveyUploadSettings(
                url = it[URL].orEmpty(),
                user = it[USER].orEmpty(),
                password = it[PASSWORD].orEmpty(),
                automatic = it[AUTOMATIC] ?: true,
            )
        }

    override suspend fun saveServer(url: String, user: String, password: String): Boolean {
        var changed = false
        store.edit {
            changed = it[URL].orEmpty() != url.trim()
            it[URL] = url.trim()
            it[USER] = user.trim()
            it[PASSWORD] = password
        }
        return changed
    }

    override suspend fun setAutomatic(automatic: Boolean) {
        store.edit { it[AUTOMATIC] = automatic }
    }

    override val keepScreenOn: Flow<Boolean> = store.data.map { it[KEEP_SCREEN_ON] ?: false }

    override suspend fun setKeepScreenOn(keepScreenOn: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = keepScreenOn }
    }

    private companion object {
        val URL = stringPreferencesKey("url")
        val USER = stringPreferencesKey("user")
        val PASSWORD = stringPreferencesKey("password")
        val AUTOMATIC = booleanPreferencesKey("automatic")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}
