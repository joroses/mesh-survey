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
package org.meshtastic.feature.survey.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.safeCatching
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.survey.R
import org.meshtastic.feature.survey.SurveyCsv
import org.meshtastic.feature.survey.SurveyDao
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveyRecorder
import org.meshtastic.feature.survey.SurveyService
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveySettings
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader
import java.util.Locale

@Suppress("LongParameterList")
@KoinViewModel
class SurveyViewModel(
    private val app: Application,
    private val recorder: SurveyRecorder,
    private val uploader: SurveyUploader,
    private val settings: SurveySettings,
    private val dao: SurveyDao,
    nodes: NodeRepository,
    service: ServiceRepository,
    private val snackbars: SnackbarManager,
    // Work that must outlive the screen: Stop, delete and export finish even if the user leaves.
    private val appScope: ApplicationCoroutineScope,
) : ViewModel() {
    val state: StateFlow<SurveyState> = recorder.state
    val uploading: StateFlow<Boolean> = uploader.uploading

    /** The stored sessions, with the recording one's measurement count from the recorder, which the list lags. */
    val sessions: StateFlow<List<SurveySessionSummary>> =
        combine(
            dao.sessions(),
            state.map { it.session?.id to it.measurementCount }.distinctUntilChanged(),
        ) { sessions, (liveId, liveCount) ->
            sessions.map { if (it.session.id == liveId) it.copy(measurementCount = liveCount) else it }
        }
            .stateInWhileSubscribed(emptyList())
    val uploadSettings: StateFlow<SurveyUploadSettings?> = settings.settings.stateInWhileSubscribed(null)

    /** The connected radio's name, which Start would survey with; null while no radio is connected. */
    val radioName: StateFlow<String?> =
        combine(service.connectionState, nodes.ourNodeInfo) { connection, node ->
            node
                ?.takeIf { connection == ConnectionState.Connected }
                ?.let { it.user.long_name.ifBlank { "!%08x".format(Locale.ROOT, it.num) } }
        }
            .stateInWhileSubscribed(null)

    /**
     * Short names of the nodes in this session's relay guesses. The node database is the connected radio's, which is
     * the survey radio; a node missing from it has no entry here.
     */
    val relayNames: StateFlow<Map<Long, String>> =
        combine(
            state
                .map { it.relays.values.flatMapTo(mutableSetOf()) { relay -> relay.guess.nodeIds } }
                .distinctUntilChanged(),
            nodes.nodeDBbyNum,
        ) { ids, db ->
            ids.mapNotNull { id -> db[id.toInt()]?.user?.short_name?.takeIf { it.isNotBlank() }?.let { id to it } }
                .toMap()
        }
            .stateInWhileSubscribed(emptyMap())

    /** Call once location (and, on Android 13+, notification) permission is granted. */
    fun start() = SurveyService.start(app)

    /** Location was refused, or granted only approximately. */
    fun noLocation() = recorder.report(SurveyProblem.NO_LOCATION)

    fun stop() {
        appScope.launch { recorder.stop() }
    }

    fun saveServer(newSettings: SurveyUploadSettings) {
        appScope.launch { settings.saveServer(newSettings.url, newSettings.user, newSettings.password) }
    }

    fun setAutomatic(automatic: Boolean) {
        appScope.launch { settings.setAutomatic(automatic) }
    }

    fun upload() = uploader.uploadNow()

    fun delete(sessionId: String) {
        if (state.value.session?.id == sessionId) return
        appScope.launch {
            val result = safeCatching { dao.deleteSession(sessionId) }
            snackbars.showSnackbar(
                app.getString(if (result.isSuccess) R.string.survey_deleted else R.string.survey_delete_failed),
            )
        }
    }

    fun export(sessionId: String, uri: Uri) {
        appScope.launch {
            val result = safeCatching {
                checkNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { out ->
                    SurveyCsv.export(dao, sessionId) { out.write(it) }
                }
            }
            snackbars.showSnackbar(
                app.getString(if (result.isSuccess) R.string.survey_export_saved else R.string.survey_export_failed),
            )
        }
    }
}
