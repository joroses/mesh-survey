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
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.ui.component.EmptyState
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.ChevronRight
import org.meshtastic.core.ui.icon.CloudUpload
import org.meshtastic.core.ui.icon.ErrorOutline
import org.meshtastic.core.ui.icon.History
import org.meshtastic.core.ui.icon.Info
import org.meshtastic.core.ui.icon.LinkOff
import org.meshtastic.core.ui.icon.LocationDisabled
import org.meshtastic.core.ui.icon.MeshRadio
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.MyLocation
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Route
import org.meshtastic.core.ui.icon.Snr
import org.meshtastic.core.ui.icon.Visibility
import org.meshtastic.core.ui.icon.VisibilityOff
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import org.meshtastic.core.ui.theme.StatusColors.StatusYellow
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.feature.survey.DIRECT_HISTORY
import org.meshtastic.feature.survey.DirectSender
import org.meshtastic.feature.survey.FAR
import org.meshtastic.feature.survey.R
import org.meshtastic.feature.survey.RelayCandidate
import org.meshtastic.feature.survey.RelayGuess
import org.meshtastic.feature.survey.SurveyFix
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveyRecorder
import org.meshtastic.feature.survey.SurveySession
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyStatus
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader
import org.meshtastic.feature.survey.isUploadUrl
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurveyScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SurveyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val radioName by viewModel.radioName.collectAsStateWithLifecycle()
    val relayNames by viewModel.relayNames.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val uploadSettings by viewModel.uploadSettings.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val keepScreenOn by viewModel.keepScreenOn.collectAsStateWithLifecycle()
    // Only while this screen is in front: Android drops the flag once the app is in the background.
    KeepScreenOn(keepScreenOn && state.session != null)

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
    val uploadsOn = uploadSettings?.url?.isNotBlank() == true

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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "recording") {
                RecordingCard(
                    state,
                    radioName,
                    relayNames,
                    keepScreenOn = keepScreenOn,
                    onKeepScreenOn = viewModel::setKeepScreenOn,
                    onStart = requestStart,
                    onStop = viewModel::stop,
                )
            }
            uploadSettings?.let { current ->
                item(key = "upload") {
                    UploadCard(
                        current,
                        sessions.firstOrNull { it.session.id == state.session?.id },
                        uploading,
                        onSave = viewModel::saveServer,
                        onAutomatic = viewModel::setAutomatic,
                        onUpload = viewModel::upload,
                    )
                }
            }
            item(key = "sessions") {
                Text(
                    stringResource(R.string.survey_sessions),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (sessions.isEmpty()) {
                item(key = "no_sessions") {
                    EmptyState(
                        icon = MeshtasticIcons.History,
                        title = stringResource(R.string.survey_no_sessions),
                        supportingText = stringResource(R.string.survey_no_sessions_hint),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            items(sessions, key = { it.session.id }) { summary ->
                SessionCard(
                    summary = summary,
                    recording = summary.session.id == state.session?.id,
                    uploadsOn = uploadsOn,
                    uploading = uploading,
                    onUpload = { viewModel.upload(summary.session.id) },
                    onExport = {
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
private const val SECONDS_PER_HOUR = 3_600L

// SigSleuth skips a measurement whose fix is older or less accurate than this (its MAX_FIX_AGE_MS and MAX_ACCURACY_M).
private const val SIGSLEUTH_MAX_FIX_AGE_S = 10
private const val SIGSLEUTH_MAX_ACCURACY_M = 100

@Composable
private fun RecordingCard(
    state: SurveyState,
    radioName: String?,
    relayNames: Map<Long, String>,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val session = state.session
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusHeader(state)
            if (session == null) {
                IdleDetails(state.lastEnd, radioName)
            } else {
                RecordingDetails(state, session, relayNames)
            }
            state.problem?.let { ProblemBanner(it) }
            KeepScreenOnRow(keepScreenOn, onKeepScreenOn)
            if (session == null) {
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        MeshtasticIcons.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.survey_start))
                }
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.survey_stop))
                }
            }
        }
    }
}

/** The whole row toggles, so the label is part of the touch target and is read with the switch. */
@Composable
private fun KeepScreenOnRow(keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = keepScreenOn, role = Role.Switch, onValueChange = onKeepScreenOn)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.survey_keep_screen_on),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(checked = keepScreenOn, onCheckedChange = null)
    }
}

/** A coloured dot and what the survey is doing; while recording, how long it has run. */
@Composable
private fun StatusHeader(state: SurveyState) {
    val session = state.session
    val (title, dot) =
        when {
            session == null -> R.string.survey_not_recording to MaterialTheme.colorScheme.outline
            state.waitingForRadio -> R.string.survey_waiting_title to MaterialTheme.colorScheme.StatusYellow
            else -> R.string.survey_recording to MaterialTheme.colorScheme.StatusGreen
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        session?.let {
            Text(
                clockText(currentTime() - it.startedAt),
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
private fun IdleDetails(lastEnd: SurveyStatus?, radioName: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.survey_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (radioName != null) {
            InfoRow(MeshtasticIcons.MeshRadio, stringResource(R.string.survey_radio_connected, radioName))
        } else {
            InfoRow(MeshtasticIcons.LinkOff, stringResource(R.string.survey_radio_none))
        }
        lastEnd?.endedNotice()?.let { InfoRow(MeshtasticIcons.Info, it) }
    }
}

@Composable
private fun RecordingDetails(state: SurveyState, session: SurveySession, relayNames: Map<Long, String>) {
    var showRelays by rememberSaveable { mutableStateOf(false) }
    val last = state.lastMeasurement
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            radioText(session),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.waitingForRadio) {
            Text(
                stringResource(R.string.survey_waiting_explained, SurveyRecorder.RADIO_WAIT.inWholeMinutes.toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(
                value = state.measurementCount.toString(),
                label = stringResource(R.string.survey_stat_measurements),
                modifier = Modifier.weight(1f),
            )
            Stat(
                value = state.relays.size.toString(),
                label = stringResource(R.string.survey_stat_relays),
                modifier = Modifier.weight(1f),
                onClick = { showRelays = true }.takeIf { state.relays.isNotEmpty() },
                onClickLabel = stringResource(R.string.survey_relays_open),
            )
            Stat(
                value = last?.let { agoText(it.heardAtMs) } ?: "–",
                label = stringResource(R.string.survey_stat_since_last),
                modifier = Modifier.weight(1f),
            )
        }
        // While the radio is away GPS is off on purpose; the header and the line above say so.
        if (!state.waitingForRadio) GpsStatus(state.fix)
        last?.let { measurement ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoRow(
                    MeshtasticIcons.Snr,
                    stringResource(
                        R.string.survey_last_signal,
                        measurement.rxSnr?.let { NumberFormatter.format(it, 2) } ?: "–",
                        measurement.rxRssi?.toString() ?: "–",
                    ),
                )
                measurement.relayByte?.let { byte ->
                    val relay = relayText(byte, state.relays[byte]?.guess, relayNames)
                    InfoRow(MeshtasticIcons.Route, stringResource(R.string.survey_last_relay, relay))
                }
            }
        }
        if (showRelays) RelaysDialog(state, relayNames, onDismiss = { showRelays = false })
    }
}

/** One figure with its label under it; with [onClick], tappable and marked so. */
@Composable
private fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val action =
        if (onClick == null) {
            Modifier.semantics(mergeDescendants = true) {}
        } else {
            Modifier.clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        }
    Column(modifier.then(action).padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                color = if (onClick == null) Color.Unspecified else MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (onClick != null) {
                Icon(
                    MeshtasticIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The fix's accuracy and age, in the error colour when SigSleuth would skip packets heard with it. */
@Composable
private fun GpsStatus(fix: SurveyFix?) {
    val now = currentTime(monotonic = true)
    val ageMs = fix?.let { (now - it.elapsedRealtimeMs).coerceAtLeast(0) }
    val usable = fix != null && ageMs != null && usableFix(ageMs, fix.accuracyM)
    val color = if (usable) Color.Unspecified else MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (fix == null || ageMs == null) {
            InfoRow(MeshtasticIcons.LocationDisabled, stringResource(R.string.survey_gps_none), color)
        } else {
            InfoRow(
                MeshtasticIcons.MyLocation,
                stringResource(
                    R.string.survey_gps,
                    fix.accuracyM?.let { NumberFormatter.format(it, 0) } ?: "?",
                    (ageMs / MILLIS_PER_SECOND).toString(),
                ),
                color,
            )
        }
        if (!usable) {
            Text(
                stringResource(R.string.survey_gps_skipped, SIGSLEUTH_MAX_FIX_AGE_S, SIGSLEUTH_MAX_ACCURACY_M),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.padding(start = INFO_TEXT_INDENT),
            )
        }
    }
}

private fun usableFix(ageMs: Long, accuracyM: Float?): Boolean =
    ageMs <= SIGSLEUTH_MAX_FIX_AGE_S * MILLIS_PER_SECOND && (accuracyM ?: 0f) <= SIGSLEUTH_MAX_ACCURACY_M

private val INFO_ICON_SIZE = 18.dp
private val INFO_ICON_GAP = 8.dp
private val INFO_TEXT_INDENT = INFO_ICON_SIZE + INFO_ICON_GAP

/** A small icon and a line of text; the icon takes [color] too, or a muted one by default. */
@Composable
private fun InfoRow(icon: ImageVector, text: String, color: Color = Color.Unspecified) {
    Row(horizontalArrangement = Arrangement.spacedBy(INFO_ICON_GAP)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(INFO_ICON_SIZE),
            tint = color.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** The problem, and for location the way to fix it, since a refused permission is never asked again. */
@Composable
private fun ProblemBanner(problem: SurveyProblem) {
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MeshtasticIcons.ErrorOutline, contentDescription = null)
            Text(
                stringResource(problem.label()),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (problem == SurveyProblem.NO_LOCATION) {
                TextButton(
                    onClick = { openLocationSettings(context) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer),
                ) {
                    Text(stringResource(R.string.survey_open_settings))
                }
            }
        }
    }
}

/** The app's permissions while precise location is not granted, otherwise the system's location switch. */
private fun openLocationSettings(context: Context) {
    val precise =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val intent =
        if (precise) {
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A device without that screen; the banner still says what to change.
    }
}

/** Why the last session ended, when it ended on its own; a Stop needs no explaining. */
@Composable
@ReadOnlyComposable
private fun SurveyStatus.endedNotice(): String? = when (this) {
    SurveyStatus.RECORDING,
    SurveyStatus.STOPPED,
    -> null

    SurveyStatus.NODE_CHANGED -> stringResource(R.string.survey_ended_node_changed)

    SurveyStatus.LORA_CHANGED -> stringResource(R.string.survey_ended_lora_changed)

    SurveyStatus.RADIO_LOST ->
        stringResource(R.string.survey_ended_radio_lost, SurveyRecorder.RADIO_WAIT.inWholeMinutes.toInt())

    SurveyStatus.INTERRUPTED -> stringResource(R.string.survey_ended_interrupted)
}

/** The session's radio, preset and frequency: "Base · LongFast · 869.525 MHz". */
@Composable
private fun radioText(session: SurveySession): String {
    val node = session.receiverName.ifBlank { "!%08x".format(Locale.ROOT, session.receiverNodeId) }
    val preset = session.modemPreset?.let(::presetName) ?: "SF${session.spreadingFactor}"
    val frequency = session.frequencyMhz ?: return stringResource(R.string.survey_node, node, preset)
    return stringResource(
        R.string.survey_node_frequency,
        node,
        preset,
        NumberFormatter.format(frequency, decimalPlaces = 3),
    )
}

/** LONG_FAST as LongFast, the way channel names show presets. */
private fun presetName(preset: String): String =
    preset.split('_').joinToString("") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

/** The relay byte with who most likely sent it on, worded as the guess it is. */
@Composable
private fun relayText(relayByte: Int, guess: RelayGuess?, names: Map<Long, String>): String {
    val byte = "%02x".format(Locale.ROOT, relayByte)
    fun name(id: Long) = nodeName(id, names)
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
private const val METERS_PER_KM = 1_000.0
private const val METERS_ROUNDING = 10

private fun nodeName(id: Long, names: Map<Long, String>) = names[id] ?: "!%08x".format(Locale.ROOT, id)

/** This session's relay bytes, most packets first, each with its guess and every candidate with how it is known. */
@Composable
private fun RelaysDialog(state: SurveyState, names: Map<Long, String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.survey_relays_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.survey_relays_explained),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.relays.entries
                    .sortedByDescending { it.value.packets }
                    .forEach { (byte, relay) ->
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    relayText(byte, relay.guess, names),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.survey_relay_packets,
                                        relay.packets,
                                        DateFormatter.formatRelativeTime(relay.lastHeardMs),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Every candidate, not just the guessed tier, so the list shows how unsure the guess is.
                            relay.candidates.forEach { candidate ->
                                val detail =
                                    when (candidate) {
                                        is RelayCandidate.Heard ->
                                            state.neighbours[candidate.nodeId]?.let { candidateText(it, state.fix) }

                                        is RelayCandidate.Reported -> reportedText(candidate, state.fix)
                                    }
                                detail?.let { RelayCandidateLine(nodeName(candidate.nodeId, names), it) }
                            }
                            // Nodes heard here too weakly to have sent this packet, so the list says why they left.
                            relay.ruledOut.forEach { node ->
                                RelayCandidateLine(
                                    nodeName(node.nodeId, names),
                                    stringResource(
                                        R.string.survey_relay_ruled_out,
                                        node.strongestHere.roundToInt(),
                                        node.packetSignal.roundToInt(),
                                    ),
                                )
                            }
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.survey_close)) } },
    )
}

/** A node's short name with how it is known, or why it was ruled out. */
@Composable
private fun RelayCandidateLine(name: String, detail: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.bodySmall)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Distance to the nearest reception cell and when that cell was heard, or without a fix the latest direct time. A later
 * direct reception elsewhere is left out to keep the line short.
 */
@Composable
private fun candidateText(sender: DirectSender, fix: SurveyFix?): String {
    val nearest =
        fix?.let { sender.nearest(it.latitude, it.longitude, nowMillis - DIRECT_HISTORY.inWholeMilliseconds) }
            ?: return stringResource(
                R.string.survey_relay_candidate,
                DateFormatter.formatRelativeTime(sender.lastDirectMs),
            )
    return stringResource(
        R.string.survey_relay_candidate_at,
        distanceText(nearest.meters),
        DateFormatter.formatRelativeTime(nearest.heardAtMs),
    )
}

/** Distance from here to where [candidate] says it is, which is a claim rather than a reception. */
@Composable
@ReadOnlyComposable
private fun reportedText(candidate: RelayCandidate.Reported, fix: SurveyFix?): String {
    fix ?: return stringResource(R.string.survey_relay_candidate_reported, distanceText(FAR))
    val meters = latLongToMeter(candidate.latitude, candidate.longitude, fix.latitude, fix.longitude)
    return stringResource(R.string.survey_relay_candidate_reported_at, distanceText(meters))
}

@Composable
@ReadOnlyComposable
private fun distanceText(meters: Double): String = if (meters < METERS_PER_KM) {
    stringResource(R.string.survey_distance_m, (meters / METERS_ROUNDING).roundToInt() * METERS_ROUNDING)
} else {
    stringResource(R.string.survey_distance_km, NumberFormatter.format(meters / METERS_PER_KM, 1))
}

/** The wall clock, or with [monotonic] the clock GPS fixes are timed on, updated every second. */
@Composable
private fun currentTime(monotonic: Boolean = false): Long {
    fun read() = if (monotonic) SystemClock.elapsedRealtime() else nowMillis
    var now by remember(monotonic) { mutableLongStateOf(read()) }
    LaunchedEffect(monotonic) {
        while (true) {
            now = read()
            delay(MILLIS_PER_SECOND)
        }
    }
    return now
}

/** A duration as h:mm:ss. */
private fun clockText(millis: Long): String {
    val seconds = (millis / MILLIS_PER_SECOND).coerceAtLeast(0)
    return "%d:%02d:%02d"
        .format(
            Locale.ROOT,
            seconds / SECONDS_PER_HOUR,
            seconds / SECONDS_PER_MINUTE % SECONDS_PER_MINUTE,
            seconds % SECONDS_PER_MINUTE,
        )
}

/** How long ago [timeMs] was, coarsely: 12 s, 3 min, 2 h. */
@Composable
private fun agoText(timeMs: Long): String {
    val seconds = ((currentTime() - timeMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> stringResource(R.string.survey_seconds, seconds.toInt())
        seconds < SECONDS_PER_HOUR -> stringResource(R.string.survey_minutes, (seconds / SECONDS_PER_MINUTE).toInt())
        else -> stringResource(R.string.survey_hours, (seconds / SECONDS_PER_HOUR).toInt())
    }
}

/**
 * Where uploads go, folded to one line once set: a server is entered once, so the form only opens on Set up or Edit.
 * Its status and Upload now are about [recording], the session recording now; ended sessions have their own button.
 */
@Composable
private fun UploadCard(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    uploading: Boolean,
    onSave: (SurveyUploadSettings) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onUpload: (String) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.survey_upload),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!editing) {
                    TextButton(onClick = { editing = true }) {
                        val action =
                            if (settings.url.isBlank()) R.string.survey_upload_set_up else R.string.survey_upload_edit
                        Text(stringResource(action))
                    }
                }
            }
            when {
                editing ->
                    ServerForm(
                        settings,
                        onSave = { edited ->
                            if (edited != settings) onSave(edited)
                            editing = false
                        },
                        onCancel = { editing = false },
                    )

                settings.url.isBlank() ->
                    Text(
                        stringResource(R.string.survey_upload_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                else -> UploadSummary(settings, recording, uploading, onAutomatic, onUpload)
            }
        }
    }
}

@Composable
private fun UploadSummary(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    uploading: Boolean,
    onAutomatic: (Boolean) -> Unit,
    onUpload: (String) -> Unit,
) {
    val pending = recording?.pendingCount ?: 0
    val error = recording?.session?.lastUploadError?.takeIf { pending > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            settings.url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        when {
            recording == null -> Unit

            error != null ->
                InfoRow(MeshtasticIcons.ErrorOutline, uploadErrorText(error), MaterialTheme.colorScheme.error)

            pending > 0 ->
                InfoRow(
                    MeshtasticIcons.CloudUpload,
                    pluralStringResource(R.plurals.survey_upload_pending, pending, pending),
                )

            else -> InfoRow(MeshtasticIcons.CheckCircle, stringResource(R.string.survey_upload_done))
        }
        // The whole row toggles, so the label is part of the touch target and is read with the switch.
        Row(
            Modifier.fillMaxWidth()
                .toggleable(value = settings.automatic, role = Role.Switch, onValueChange = onAutomatic)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.survey_upload_automatic),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = settings.automatic, onCheckedChange = null)
        }
        // Ended sessions are uploaded from their own cards, so nothing is sent that the user did not pick.
        recording?.let { current ->
            OutlinedButton(
                onClick = { onUpload(current.session.id) },
                enabled = pending > 0 && !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uploading) {
                    CircularProgressIndicator(
                        Modifier.size(ButtonDefaults.IconSize),
                        color = LocalContentColor.current,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.survey_uploading))
                } else {
                    Text(stringResource(R.string.survey_upload_now))
                }
            }
        }
    }
}

/** Measurements SigSleuth does not have yet. */
private val SurveySessionSummary.pendingCount: Int
    get() = (measurementCount - uploadedCount).coerceAtLeast(0)

/** A refused password is the one failure the user fixes here; [SurveyUploader] words it "HTTP 401" or "HTTP 403". */
@Composable
@ReadOnlyComposable
private fun uploadErrorText(error: String): String = if (error == "HTTP 401" || error == "HTTP 403") {
    stringResource(R.string.survey_upload_failed_password, error)
} else {
    stringResource(R.string.survey_upload_failed, error)
}

@Composable
private fun ServerForm(settings: SurveyUploadSettings, onSave: (SurveyUploadSettings) -> Unit, onCancel: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(settings.url) }
    var user by rememberSaveable { mutableStateOf(settings.user) }
    var password by rememberSaveable { mutableStateOf(settings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val edited = SurveyUploadSettings(url.trim(), user.trim(), password, settings.automatic)
    val urlInvalid = edited.url.isNotEmpty() && !isUploadUrl(edited.url)
    val save = { if (!urlInvalid) onSave(edited) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.survey_upload_url)) },
            placeholder = { Text("https://sigsleuth.example.org") },
            isError = urlInvalid,
            supportingText = {
                Text(
                    stringResource(
                        if (urlInvalid) R.string.survey_upload_url_invalid else R.string.survey_upload_url_hint,
                    ),
                )
            },
            singleLine = true,
            keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text(stringResource(R.string.survey_upload_user)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.survey_upload_password)) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    if (showPassword) {
                        Icon(
                            MeshtasticIcons.VisibilityOff,
                            contentDescription = stringResource(R.string.survey_upload_hide_password),
                        )
                    } else {
                        Icon(
                            MeshtasticIcons.Visibility,
                            contentDescription = stringResource(R.string.survey_upload_show_password),
                        )
                    }
                }
            },
            keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.survey_cancel)) }
            Button(onClick = save, enabled = !urlInvalid) { Text(stringResource(R.string.survey_save)) }
        }
    }
}

@Composable
private fun SessionCard(
    summary: SurveySessionSummary,
    recording: Boolean,
    uploadsOn: Boolean,
    uploading: Boolean,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val session = summary.session
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (recording) 16.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    DateFormatter.formatDateTime(session.startedAt),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (recording) {
                    Text(
                        stringResource(R.string.survey_recording),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(radioText(session), style = MaterialTheme.typography.bodyMedium)
            Text(
                sessionFacts(summary, recording),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            UploadProgress(summary, uploadsOn)
            serverText(session)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            session.lastUploadError?.let {
                Text(
                    uploadErrorText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // A session still recording can be neither exported whole nor deleted, so it offers neither.
            if (!recording) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { confirmDelete = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) {
                        Text(stringResource(R.string.survey_delete))
                    }
                    TextButton(onClick = onExport) { Text(stringResource(R.string.survey_export)) }
                    // Disabled, not spinning, during a run: the run may be another session's.
                    if (uploadsOn && summary.pendingCount > 0) {
                        TextButton(onClick = onUpload, enabled = !uploading) {
                            Text(stringResource(R.string.survey_upload_session))
                        }
                    }
                }
            }
        }
        if (confirmDelete) {
            DeleteDialog(
                summary,
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

/** Length, size and how it ended: "1:12:03 · 1234 measurements · Stopped". */
@Composable
private fun sessionFacts(summary: SurveySessionSummary, recording: Boolean): String {
    val session = summary.session
    val count = pluralStringResource(R.plurals.survey_measurements, summary.measurementCount, summary.measurementCount)
    if (recording) return count
    val duration = session.endedAt?.let { clockText(it - session.startedAt) }
    return listOfNotNull(duration, count, stringResource(session.status.label())).joinToString(" · ")
}

/** How much SigSleuth has; nothing when nothing was ever sent and uploads are off or the session is private. */
@Composable
private fun UploadProgress(summary: SurveySessionSummary, uploadsOn: Boolean) {
    val count = summary.measurementCount
    val uploaded = summary.uploadedCount.coerceAtMost(count)
    if (count == 0 || ((!uploadsOn || !summary.session.upload) && uploaded == 0)) return
    if (uploaded == count) {
        InfoRow(MeshtasticIcons.CheckCircle, stringResource(R.string.survey_uploaded_all))
    } else {
        Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.survey_uploaded_of, uploaded, count),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(progress = { uploaded.toFloat() / count }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** What SigSleuth kept and skipped, with the reasons for the skips. */
@Composable
private fun serverText(session: SurveySession): String? {
    if (session.serverKept + session.serverSkipped == 0L) return null
    val reasons =
        SurveyUploader.decodeSkipReasons(session.serverSkipReasons).entries.map { (reason, count) ->
            skipReasonText(reason, count)
        }
    return if (reasons.isEmpty()) {
        stringResource(R.string.survey_server_kept, session.serverKept, session.serverSkipped)
    } else {
        stringResource(
            R.string.survey_server_kept_reasons,
            session.serverKept,
            session.serverSkipped,
            reasons.joinToString(),
        )
    }
}

/** SigSleuth's reason names (coverage.py `skip_reason`) in words. */
@Composable
@ReadOnlyComposable
private fun skipReasonText(reason: String, count: Long): String = when (reason) {
    "no_position" -> stringResource(R.string.survey_skip_no_position, count)
    "inaccurate" -> stringResource(R.string.survey_skip_inaccurate, count, SIGSLEUTH_MAX_ACCURACY_M)
    "old_fix" -> stringResource(R.string.survey_skip_old_fix, count, SIGSLEUTH_MAX_FIX_AGE_S)
    "too_old" -> stringResource(R.string.survey_skip_too_old, count)
    "in_future" -> stringResource(R.string.survey_skip_in_future, count)
    "no_sender" -> stringResource(R.string.survey_skip_no_sender, count)
    else -> stringResource(R.string.survey_skip_other, count, reason.replace('_', ' '))
}

@Composable
private fun DeleteDialog(summary: SurveySessionSummary, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val count = summary.measurementCount
    val kept = summary.session.serverKept.coerceAtMost(count.toLong())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.survey_delete_title)) },
        text = {
            Text(
                when {
                    count == 0 -> stringResource(R.string.survey_delete_text_empty)

                    // An uploaded row SigSleuth skipped was never stored, so it is not safe to delete.
                    summary.session.serverSkipped > 0 -> stringResource(R.string.survey_delete_text_kept, kept, count)

                    summary.uploadedCount >= count -> stringResource(R.string.survey_delete_text_uploaded, count)

                    else -> stringResource(R.string.survey_delete_text, summary.uploadedCount, count)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.survey_delete))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.survey_cancel)) } },
    )
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
