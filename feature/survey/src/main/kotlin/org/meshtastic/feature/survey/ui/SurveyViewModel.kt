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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.safeCatching
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
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

/** Outcome of the last CSV export, for a one-line message on the screen. */
enum class ExportResult {
    SAVED,
    FAILED,
}

@KoinViewModel
class SurveyViewModel(
    private val app: Application,
    private val recorder: SurveyRecorder,
    private val uploader: SurveyUploader,
    private val settings: SurveySettings,
    private val dao: SurveyDao,
    nodes: NodeRepository,
    // Work that must outlive the screen: Stop, delete and export finish even if the user leaves.
    private val appScope: ApplicationCoroutineScope,
) : ViewModel() {
    val state: StateFlow<SurveyState> = recorder.state
    val uploading: StateFlow<Boolean> = uploader.uploading
    val sessions: StateFlow<List<SurveySessionSummary>> = dao.sessions().stateInWhileSubscribed(emptyList())
    val uploadSettings: StateFlow<SurveyUploadSettings?> = settings.settings.stateInWhileSubscribed(null)

    /**
     * Short names of the nodes in the last relay guess. The node database is the connected radio's, which is the survey
     * radio; a node missing from it has no entry here.
     */
    val relayNames: StateFlow<Map<Long, String>> =
        combine(state.map { it.lastRelayGuess?.nodeIds.orEmpty() }.distinctUntilChanged(), nodes.nodeDBbyNum) {
                ids,
                db,
            ->
            ids.mapNotNull { id -> db[id.toInt()]?.user?.short_name?.takeIf { it.isNotBlank() }?.let { id to it } }
                .toMap()
        }
            .stateInWhileSubscribed(emptyMap())

    private val mutableExportResult = MutableStateFlow<ExportResult?>(null)
    val exportResult: StateFlow<ExportResult?> = mutableExportResult.asStateFlow()

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
        appScope.launch { safeCatching { dao.deleteSession(sessionId) } }
    }

    fun export(sessionId: String, uri: Uri) {
        appScope.launch {
            val result = safeCatching {
                checkNotNull(app.contentResolver.openOutputStream(uri)).bufferedWriter().use { out ->
                    SurveyCsv.export(dao, sessionId) { out.write(it) }
                }
            }
            mutableExportResult.value = if (result.isSuccess) ExportResult.SAVED else ExportResult.FAILED
        }
    }

    fun clearExportResult() {
        mutableExportResult.value = null
    }
}
