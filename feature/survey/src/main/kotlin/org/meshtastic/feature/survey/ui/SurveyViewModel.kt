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
import co.touchlab.kermit.Logger
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
import org.meshtastic.feature.survey.SurveyDeleted
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveyRecorder
import org.meshtastic.feature.survey.SurveyService
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveySettings
import org.meshtastic.feature.survey.SurveyStartRequest
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader
import org.meshtastic.feature.survey.publishSurveyShortcut
import java.util.Locale

@Suppress("LongParameterList")
@KoinViewModel
class SurveyViewModel(
    private val app: Application,
    private val recorder: SurveyRecorder,
    private val uploader: SurveyUploader,
    private val settings: SurveySettings,
    private val dao: SurveyDao,
    private val startRequest: SurveyStartRequest,
    nodes: NodeRepository,
    service: ServiceRepository,
    private val snackbars: SnackbarManager,
    // Work that must outlive the screen: Stop, delete and export finish even if the user leaves.
    private val appScope: ApplicationCoroutineScope,
) : ViewModel() {
    val state: StateFlow<SurveyState> = recorder.state
    val uploading: StateFlow<Boolean> = uploader.uploading

    /** The Quick Settings tile's start request; the screen calls [takeStartRequest] when it changes. */
    val startRequests: StateFlow<Long?> = startRequest.pending

    init {
        // Offered once the screen has been found, then kept: a launcher shortcut costs nothing until used.
        appScope.launch {
            safeCatching { publishSurveyShortcut(app) }.onFailure { Logger.w { "Survey shortcut not published: $it" } }
        }
    }

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
    val keepScreenOn: StateFlow<Boolean> = settings.keepScreenOn.stateInWhileSubscribed(false)

    /** What deleted sessions had added to SigSleuth's map, for the Sessions card's total. */
    val deleted: StateFlow<SurveyDeleted?> = dao.deleted().stateInWhileSubscribed(null)

    /** The distinct senders every session heard directly, for the Sessions card's total. */
    val directSenders: StateFlow<Int> = dao.directSenders().stateInWhileSubscribed(0)

    /** The connected radio's name, which Start would survey with; null while no radio is connected. */
    val radioName: StateFlow<String?> =
        combine(service.connectionState, nodes.ourNodeInfo) { connection, node ->
            node
                ?.takeIf { connection == ConnectionState.Connected }
                ?.let { it.user.long_name.ifBlank { "!%08x".format(Locale.ROOT, it.num) } }
        }
            .stateInWhileSubscribed(null)

    /**
     * Short names of the nodes in this session's relay guesses, candidates, ruled-out nodes and SigSleuth's answers.
     * The node database is the connected radio's, which is the survey radio; SigSleuth names the nodes missing from it.
     */
    val relayNames: StateFlow<Map<Long, String>> =
        combine(
            state
                .map { current ->
                    val sigSleuth = mutableMapOf<Long, String>()
                    val ids =
                        current.relays.values.flatMapTo(mutableSetOf()) { relay ->
                            relay.server?.let { answer ->
                                answer.nodeId?.let { id -> answer.shortName?.let { sigSleuth[id] = it } }
                            }
                            relay.guess.nodeIds +
                                relay.candidates.map { it.nodeId } +
                                relay.ruledOut.map { it.nodeId } +
                                listOfNotNull(relay.server?.nodeId)
                        }
                    ids.forEach { id -> current.mapped?.names?.get(id)?.let { sigSleuth.putIfAbsent(id, it) } }
                    ids to sigSleuth.toMap()
                }
                .distinctUntilChanged(),
            nodes.nodeDBbyNum,
        ) { (ids, sigSleuth), db ->
            ids.mapNotNull { id ->
                (db[id.toInt()]?.user?.short_name?.takeIf { it.isNotBlank() } ?: sigSleuth[id])?.let {
                    id to it
                }
            }
                .toMap()
        }
            .stateInWhileSubscribed(emptyMap())

    /** Call once location (and, on Android 13+, notification) permission is granted. */
    fun start() = SurveyService.start(app)

    /** True when the tile asked for a start that should happen now: it is fresh and nothing records yet. */
    fun takeStartRequest(): Boolean = startRequest.take() && state.value.session == null

    /** Location was refused, or granted only approximately. */
    fun noLocation() = recorder.report(SurveyProblem.NO_LOCATION)

    fun stop() {
        appScope.launch { recorder.stop() }
    }

    fun saveServer(newSettings: SurveyUploadSettings) {
        appScope.launch { uploader.saveServer(newSettings.url, newSettings.user, newSettings.password) }
    }

    /** The session recording now follows the switch; ended sessions keep what they have. */
    fun setAutomatic(automatic: Boolean) {
        appScope.launch { uploader.setAutomatic(automatic) { state.value.session?.id } }
    }

    fun setKeepScreenOn(keepScreenOn: Boolean) {
        appScope.launch { settings.setKeepScreenOn(keepScreenOn) }
    }

    fun upload(sessionId: String) = uploader.uploadNow(sessionId)

    fun uploadAll() = uploader.uploadAll()

    fun delete(sessionId: String) {
        // A delete recounts later sessions, and the recording one keeps earlier sessions' keys from its start.
        if (state.value.session != null) return
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
