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
@file:Suppress("TooManyFunctions", "LongMethod")

package org.meshtastic.feature.survey.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.feature.survey.R
import org.meshtastic.feature.survey.RelayGuess
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyStatus
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader
import org.meshtastic.feature.survey.isUploadUrl
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SurveyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val relayNames by viewModel.relayNames.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val uploadSettings by viewModel.uploadSettings.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val exportResult by viewModel.exportResult.collectAsStateWithLifecycle()

    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // Approximate location alone cannot place a measurement; say so rather than do nothing.
            if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) viewModel.start() else viewModel.noLocation()
        }
    val requestStart = {
        permissions.launch(
            buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
                .toTypedArray(),
        )
    }
    var exportSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val sessionId = exportSessionId
            if (uri != null && sessionId != null) viewModel.export(sessionId, uri)
        }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.survey_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(R.string.survey_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RecordingCard(state, relayNames, onStart = requestStart, onStop = viewModel::stop) }
            item {
                uploadSettings?.let { current ->
                    UploadSettingsCard(
                        current,
                        uploading,
                        onSave = viewModel::saveServer,
                        onAutomatic = viewModel::setAutomatic,
                        onUpload = viewModel::upload,
                    )
                }
            }
            exportResult?.let { result ->
                item {
                    Text(
                        stringResource(
                            if (result == ExportResult.SAVED) {
                                R.string.survey_export_saved
                            } else {
                                R.string.survey_export_failed
                            },
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            item { Text(stringResource(R.string.survey_sessions), style = MaterialTheme.typography.titleMedium) }
            if (sessions.isEmpty()) {
                item { Text(stringResource(R.string.survey_no_sessions)) }
            }
            items(sessions, key = { it.session.id }) { summary ->
                SessionCard(
                    summary = summary,
                    recording = summary.session.id == state.session?.id,
                    onUpload = viewModel::upload,
                    onExport = {
                        viewModel.clearExportResult()
                        exportSessionId = summary.session.id
                        exporter.launch(csvFileName(summary))
                    },
                    onDelete = { viewModel.delete(summary.session.id) },
                )
            }
        }
    }
}

private fun csvFileName(summary: SurveySessionSummary): String =
    "signal-survey-${summary.session.id.take(SHORT_ID_LENGTH)}.csv"

private const val SHORT_ID_LENGTH = 8
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

@Composable
private fun RecordingCard(state: SurveyState, relayNames: Map<Long, String>, onStart: () -> Unit, onStop: () -> Unit) {
    val session = state.session
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(
                    when {
                        session == null -> R.string.survey_not_recording
                        state.waitingForRadio -> R.string.survey_waiting_for_radio
                        else -> R.string.survey_recording
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            if (session == null) {
                Text(stringResource(R.string.survey_intro), style = MaterialTheme.typography.bodyMedium)
                state.lastEnd?.let { Text(stringResource(R.string.survey_last_ended, stringResource(it.label()))) }
            } else {
                val node = session.receiverName.ifBlank { "!%08x".format(Locale.ROOT, session.receiverNodeId) }
                Text(stringResource(R.string.survey_node, node, session.modemPreset ?: "SF${session.spreadingFactor}"))
                Text(stringResource(R.string.survey_duration, elapsedText(session.startedAt)))
                Text(
                    pluralStringResource(R.plurals.survey_measurements, state.measurementCount, state.measurementCount),
                )
                Text(pluralStringResource(R.plurals.survey_relays, state.relays.size, state.relays.size))
                state.lastMeasurement?.let { last ->
                    Text(stringResource(R.string.survey_since_last, elapsedText(last.heardAtMs)))
                }
                Text(gpsText(state))
                state.lastMeasurement?.let { last ->
                    Text(
                        stringResource(
                            R.string.survey_last_packet,
                            last.rxSnr?.let { NumberFormatter.format(it, 2) } ?: "–",
                            last.rxRssi?.toString() ?: "–",
                            last.relayByte?.let { relayText(it, state.lastRelayGuess, relayNames) } ?: "–",
                        ),
                    )
                }
            }
            state.problem?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error) }
            if (session == null) {
                Button(onClick = onStart) { Text(stringResource(R.string.survey_start)) }
            } else {
                Button(onClick = onStop) { Text(stringResource(R.string.survey_stop)) }
            }
        }
    }
}

/** The relay byte with who most likely sent it on, worded as the guess it is. */
@Composable
private fun relayText(relayByte: Int, guess: RelayGuess?, names: Map<Long, String>): String {
    val byte = "%02x".format(Locale.ROOT, relayByte)
    fun name(id: Long) = names[id] ?: "!%08x".format(Locale.ROOT, id)
    return when (guess) {
        is RelayGuess.Direct -> stringResource(R.string.survey_relay_direct, name(guess.senderId))

        is RelayGuess.Likely -> stringResource(R.string.survey_relay_likely, byte, name(guess.nodeId))

        is RelayGuess.Possible -> stringResource(R.string.survey_relay_possible, byte, name(guess.nodeId))

        is RelayGuess.Ambiguous -> {
            val shown = guess.nodeIds.take(MAX_RELAY_NAMES).map(::name)
            val more = if (guess.nodeIds.size > MAX_RELAY_NAMES) "…" else ""
            stringResource(
                R.string.survey_relay_ambiguous,
                byte,
                shown.reduce { joined, next -> stringResource(R.string.survey_relay_or, joined, next) } + more,
            )
        }

        RelayGuess.Unknown,
        null,
        -> byte
    }
}

private const val MAX_RELAY_NAMES = 3

@Composable
private fun gpsText(state: SurveyState): String {
    val fix = state.fix ?: return stringResource(R.string.survey_gps_none)
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(fix) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(MILLIS_PER_SECOND)
        }
    }
    val accuracy = fix.accuracyM?.let { NumberFormatter.format(it, 0) } ?: "?"
    val age = ((now - fix.elapsedRealtimeMs).coerceAtLeast(0) / MILLIS_PER_SECOND).toString()
    return stringResource(R.string.survey_gps, accuracy, age)
}

@Composable
private fun elapsedText(startedAt: Long): String {
    var now by remember { mutableLongStateOf(nowMillis) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = nowMillis
            delay(MILLIS_PER_SECOND)
        }
    }
    val seconds = ((now - startedAt) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return "%d:%02d:%02d"
        .format(
            Locale.ROOT,
            seconds / (SECONDS_PER_MINUTE * SECONDS_PER_MINUTE),
            seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
        )
}

@Composable
private fun UploadSettingsCard(
    settings: SurveyUploadSettings,
    uploading: Boolean,
    onSave: (SurveyUploadSettings) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onUpload: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(settings.url) }
    var user by rememberSaveable { mutableStateOf(settings.user) }
    var password by rememberSaveable { mutableStateOf(settings.password) }
    val edited = SurveyUploadSettings(url.trim(), user.trim(), password, settings.automatic)
    val urlInvalid = edited.url.isNotEmpty() && !isUploadUrl(edited.url)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.survey_upload), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.survey_upload_url)) },
                placeholder = { Text("https://sigsleuth.example.org") },
                isError = urlInvalid,
                supportingText =
                if (urlInvalid) {
                    { Text(stringResource(R.string.survey_upload_url_invalid)) }
                } else {
                    null
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text(stringResource(R.string.survey_upload_user)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.survey_upload_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.survey_upload_automatic), modifier = Modifier.weight(1f))
                // Saves only the switch: unsaved field edits wait for Save.
                Switch(checked = settings.automatic, onCheckedChange = onAutomatic)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onSave(edited) }, enabled = edited != settings && !urlInvalid) {
                    Text(stringResource(R.string.survey_save))
                }
                OutlinedButton(onClick = onUpload, enabled = settings.url.isNotBlank() && !uploading) {
                    Text(stringResource(R.string.survey_upload_now))
                }
                if (uploading) Text(stringResource(R.string.survey_uploading))
            }
        }
    }
}

@Composable
private fun SessionCard(
    summary: SurveySessionSummary,
    recording: Boolean,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val session = summary.session
    var confirmDelete by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormatter.formatDateTime(session.startedAt), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.survey_node,
                    session.receiverName.ifBlank { "!%08x".format(Locale.ROOT, session.receiverNodeId) },
                    session.modemPreset ?: "SF${session.spreadingFactor}",
                ),
            )
            Text(
                pluralStringResource(R.plurals.survey_measurements, summary.measurementCount, summary.measurementCount),
            )
            Text(stringResource(session.status.label()))
            Text(stringResource(R.string.survey_uploaded_of, summary.uploadedCount, summary.measurementCount))
            if (session.serverKept + session.serverSkipped > 0) {
                Text(stringResource(R.string.survey_server_kept, session.serverKept, session.serverSkipped))
            }
            SurveyUploader.decodeSkipReasons(session.serverSkipReasons)
                .takeIf { it.isNotEmpty() }
                ?.let { reasons ->
                    val text = reasons.entries.joinToString { (reason, count) -> "${reason.replace('_', ' ')} $count" }
                    Text(stringResource(R.string.survey_server_skip_reasons, text))
                }
            session.lastUploadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onUpload, enabled = summary.uploadedCount < summary.measurementCount) {
                    Text(stringResource(R.string.survey_upload_now))
                }
                TextButton(onClick = onExport, enabled = !recording) { Text(stringResource(R.string.survey_export)) }
                TextButton(onClick = { confirmDelete = true }, enabled = !recording) {
                    Text(stringResource(R.string.survey_delete))
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.survey_delete_title)) },
            text = {
                Text(stringResource(R.string.survey_delete_text, summary.uploadedCount, summary.measurementCount))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text(stringResource(R.string.survey_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.survey_cancel)) }
            },
        )
    }
}

private fun SurveyStatus.label(): Int = when (this) {
    SurveyStatus.RECORDING -> R.string.survey_status_recording
    SurveyStatus.STOPPED -> R.string.survey_status_stopped
    SurveyStatus.NODE_CHANGED -> R.string.survey_status_node_changed
    SurveyStatus.LORA_CHANGED -> R.string.survey_status_lora_changed
    SurveyStatus.RADIO_LOST -> R.string.survey_status_radio_lost
    SurveyStatus.INTERRUPTED -> R.string.survey_status_interrupted
}

private fun SurveyProblem.label(): Int = when (this) {
    SurveyProblem.NO_RADIO -> R.string.survey_problem_no_radio
    SurveyProblem.NO_LORA_SETTINGS -> R.string.survey_problem_no_lora
    SurveyProblem.NO_LOCATION -> R.string.survey_problem_no_location
    SurveyProblem.STORAGE -> R.string.survey_problem_storage
}
