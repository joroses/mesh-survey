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
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.survey_relay_server_candidate
import org.meshtastic.core.resources.survey_relay_server_candidates
import org.meshtastic.core.ui.component.EmptyState
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.ChevronRight
import org.meshtastic.core.ui.icon.CloudUpload
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.DoDisturb
import org.meshtastic.core.ui.icon.ErrorOutline
import org.meshtastic.core.ui.icon.FileDownload
import org.meshtastic.core.ui.icon.History
import org.meshtastic.core.ui.icon.Info
import org.meshtastic.core.ui.icon.LinkOff
import org.meshtastic.core.ui.icon.LocationDisabled
import org.meshtastic.core.ui.icon.LocationOn
import org.meshtastic.core.ui.icon.Map
import org.meshtastic.core.ui.icon.MeshRadio
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.More
import org.meshtastic.core.ui.icon.MyLocation
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Route
import org.meshtastic.core.ui.icon.Rssi
import org.meshtastic.core.ui.icon.Snr
import org.meshtastic.core.ui.icon.Verified
import org.meshtastic.core.ui.icon.Visibility
import org.meshtastic.core.ui.icon.VisibilityOff
import org.meshtastic.core.ui.theme.StatusColors.StatusGreen
import org.meshtastic.core.ui.theme.StatusColors.StatusOrange
import org.meshtastic.core.ui.theme.StatusColors.StatusRed
import org.meshtastic.core.ui.theme.StatusColors.StatusSky
import org.meshtastic.core.ui.theme.StatusColors.StatusYellow
import org.meshtastic.core.ui.util.KeepScreenOn
import org.meshtastic.feature.survey.DIRECT_HISTORY
import org.meshtastic.feature.survey.DirectSender
import org.meshtastic.feature.survey.FAR
import org.meshtastic.feature.survey.LinkEvidence
import org.meshtastic.feature.survey.R
import org.meshtastic.feature.survey.RelayCandidate
import org.meshtastic.feature.survey.RelayGuess
import org.meshtastic.feature.survey.RelayStats
import org.meshtastic.feature.survey.SIGSLEUTH_MAX_ACCURACY_M
import org.meshtastic.feature.survey.SIGSLEUTH_MAX_FIX_AGE_S
import org.meshtastic.feature.survey.ServerAnswer
import org.meshtastic.feature.survey.SurveyFix
import org.meshtastic.feature.survey.SurveyMeasurement
import org.meshtastic.feature.survey.SurveyNews
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveyRecorder
import org.meshtastic.feature.survey.SurveySession
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyStatus
import org.meshtastic.feature.survey.SurveyTotals
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader
import org.meshtastic.feature.survey.isUploadUrl
import org.meshtastic.feature.survey.surveyTotals
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource as sharedStringResource

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
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val directSenders by viewModel.directSenders.collectAsStateWithLifecycle()
    val totals =
        remember(sessions, deleted, directSenders) { surveyTotals(sessions.map { it.session }, deleted, directSenders) }
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
    // The Quick Settings tile opens this screen to start, as if Start was pressed. Only while resumed, so the
    // permission prompt never comes up over another app.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.startRequests.filterNotNull().collect { if (viewModel.takeStartRequest()) requestStart() }
        }
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
                title = { Text(stringResource(R.string.survey_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(R.string.survey_back))
                    }
                },
                actions = { KeepScreenOnChip(keepScreenOn, viewModel::setKeepScreenOn) },
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
                    onStart = requestStart,
                    onStop = viewModel::stop,
                )
            }
            uploadSettings?.let { current ->
                item(key = "upload") {
                    val past = sessions.filter { it.session.id != state.session?.id }
                    UploadCard(
                        current,
                        sessions.firstOrNull { it.session.id == state.session?.id },
                        pastWaiting = past.count { it.waiting },
                        anyUploaded = past.any { it.uploadedCount > 0 },
                        uploading = uploading,
                        onSave = viewModel::saveServer,
                        onAutomatic = viewModel::setAutomatic,
                        onUpload = viewModel::upload,
                        onUploadAll = viewModel::uploadAll,
                    )
                }
            }
            // Under Upload, so recording does not push the Upload card down; the lifetime progress, apart from the
            // list.
            totals?.let { item(key = "totals") { TotalsCard(it) } }
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
            itemsIndexed(sessions, key = { _, summary -> summary.session.id }) { index, summary ->
                // Newest first: a card names its radio when it differs from the session before it, the oldest always.
                val older = sessions.getOrNull(index + 1)?.session
                SessionCard(
                    summary = summary,
                    recording = summary.session.id == state.session?.id,
                    waiting = uploadsOn && summary.session.id != state.session?.id && summary.waiting,
                    deletable = state.session == null,
                    uploadsOn = uploadsOn,
                    uploading = uploading,
                    showRadio = older == null || radioKey(older) != radioKey(summary.session),
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
private const val SECONDS_PER_DAY = 86_400L

/** What [radioText] names: two sessions with the same key show the same radio line. */
private fun radioKey(session: SurveySession) = listOf(
    session.receiverNodeId,
    session.receiverName,
    session.modemPreset,
    session.spreadingFactor,
    session.frequencyMhz,
)

@Composable
private fun RecordingCard(
    state: SurveyState,
    radioName: String?,
    relayNames: Map<Long, String>,
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

/**
 * Keep screen on, in the top bar so it stays out of the cards and in view in every state. A chip rather than an icon,
 * so its words say what it does. On, it is filled in the colour of a switch that is on and checked; off, only outlined.
 */
@Composable
private fun KeepScreenOnChip(keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = keepScreenOn,
        onClick = { onKeepScreenOn(!keepScreenOn) },
        label = { Text(stringResource(R.string.survey_keep_screen_on), maxLines = 1) },
        modifier = Modifier.padding(end = 8.dp),
        leadingIcon =
        if (keepScreenOn) {
            { Icon(MeshtasticIcons.Check, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            selectedLeadingIconColor = colors.onPrimary,
        ),
    )
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
        // The figures its session card shows once it ends. Relays counts every byte heard, as the list it opens does;
        // the session card counts those heard with a fix.
        NewsStats(
            state.newToYou ?: NO_NEWS,
            relays = state.relays.size,
            mapCells = state.news?.newCells,
            onRelays = { showRelays = true }.takeIf { state.relays.isNotEmpty() },
        )
        // While the radio is away GPS is off on purpose; the header and the line above say so.
        if (!state.waitingForRadio) GpsStatus(state.fix)
        LastPacket(last, session.spreadingFactor, state.relays, relayNames)
        if (showRelays) RelaysSheet(state, session.spreadingFactor, relayNames, onDismiss = { showRelays = false })
    }
}

private val NO_NEWS = SurveyNews(cells = 0, relays = 0, direct = 0)

/** How long ago the last packet came, with its signal and relay under it; until one comes, that none has. */
@Composable
private fun LastPacket(
    last: SurveyMeasurement?,
    spreadingFactor: Int,
    relays: Map<Int, RelayStats>,
    names: Map<Long, String>,
) {
    val now = currentTime()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (last == null) {
                stringResource(R.string.survey_no_packet)
            } else {
                stringResource(R.string.survey_last_packet, agoText(last.heardAtMs, now))
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        last?.let { measurement ->
            InfoRow(MeshtasticIcons.Snr) {
                val snr = measurement.rxSnr
                SnrText(
                    stringResource(
                        R.string.survey_last_signal,
                        if (snr == null) "–" else SNR_SLOT,
                        measurement.rxRssi?.toString() ?: "–",
                    ),
                    snr,
                    SNR_DECIMALS_CARD,
                    spreadingFactor,
                )
            }
            measurement.relayByte?.let { byte ->
                val relay = relayText(byte, relays[byte], names)
                InfoRow(MeshtasticIcons.Route, relayByteMarked(stringResource(R.string.survey_last_relay, relay), byte))
            }
        }
    }
}

/**
 * One figure with its label under it, then the [note] of what was new to this phone in green and the [mapNote] of what
 * SigSleuth's map gained in its colour; with [onClick], tappable and marked so.
 */
@Composable
@Suppress("LongParameterList")
private fun Stat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    mapNote: String? = null,
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
        note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.StatusGreen)
        }
        mapNote?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.StatusSky)
        }
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

/** A small icon and a line of text; the icon takes [iconTint], which defaults to [color], or a muted one. */
@Composable
private fun InfoRow(icon: ImageVector, text: String, color: Color = Color.Unspecified, iconTint: Color = color) =
    InfoRow(icon, AnnotatedString(text), color, iconTint)

/** [InfoRow] for text with styled parts, such as the relay byte. */
@Composable
private fun InfoRow(
    icon: ImageVector,
    text: AnnotatedString,
    color: Color = Color.Unspecified,
    iconTint: Color = color,
) = InfoRow(icon, iconTint) { Text(text, style = MaterialTheme.typography.bodyMedium, color = color) }

/** [InfoRow]'s icon beside any [text], such as one with an SNR chip. */
@Composable
private fun InfoRow(icon: ImageVector, iconTint: Color = Color.Unspecified, text: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(INFO_ICON_GAP)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.padding(top = 1.dp).size(INFO_ICON_SIZE),
            tint = iconTint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant },
        )
        text()
    }
}

/**
 * [text] with [snr] drawn as a chip where [SNR_SLOT] stands in it: the SNR and its unit on roughly the colour
 * SigSleuth's map will give this spot (its fade margin), in black or white, whichever reads better. The chip sits in
 * the text, so it wraps with it, and a translation can put it anywhere. Without an SNR, [text] as it is.
 */
@Composable
private fun SnrText(text: String, snr: Float?, decimals: Int, spreadingFactor: Int, color: Color = Color.Unspecified) {
    val style = MaterialTheme.typography.bodyMedium
    val at = text.indexOf(SNR_SLOT)
    if (snr == null || at < 0) {
        Text(text, style = style, color = color)
        return
    }
    val label = stringResource(R.string.survey_snr, NumberFormatter.format(snr, decimals))
    val fill = marginColor(fadeMargin(snr, spreadingFactor))
    val chipStyle = style.copy(fontFeatureSettings = "tnum")
    // A placeholder needs its size up front: the label's width plus the chip's padding, one line high.
    val labelWidth = rememberTextMeasurer().measure(label, chipStyle, maxLines = 1).size.width
    val width = with(LocalDensity.current) { (labelWidth + 2 * SNR_CHIP_PADDING.roundToPx()).toSp() }
    val height = style.lineHeight.takeOrElse { style.fontSize * SNR_CHIP_LINE_HEIGHT }
    val chip =
        InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.TextCenter)) {
            Box(
                Modifier.fillMaxSize().background(fill, MaterialTheme.shapes.extraSmall),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = chipStyle, color = contentOn(fill), maxLines = 1)
            }
        }
    val annotated = buildAnnotatedString {
        append(text.substring(0, at))
        appendInlineContent(SNR_CHIP, label)
        append(text.substring(at + SNR_SLOT.length))
    }
    Text(annotated, style = style, color = color, inlineContent = mapOf(SNR_CHIP to chip))
}

/** Black or white, whichever has more contrast on [fill]. */
private fun contentOn(fill: Color): Color {
    val shifted = fill.luminance() + LUMINANCE_FLARE
    // Black's contrast is shifted / flare, white's (1 + flare) / shifted: black wins once shifted² passes the product.
    return if (shifted * shifted > (1 + LUMINANCE_FLARE) * LUMINANCE_FLARE) Color.Black else Color.White
}

/** Where [SnrText] puts the chip: a character no string resource contains. */
private const val SNR_SLOT = "￼"
private const val SNR_CHIP = "snr"
private const val SNR_DECIMALS_CARD = 2
private const val SNR_DECIMALS_LIST = 1
private const val SNR_CHIP_LINE_HEIGHT = 1.4f
private const val LUMINANCE_FLARE = 0.05f
private val SNR_CHIP_PADDING = 4.dp

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

/**
 * The relay byte with who sent it on: SigSleuth's answer when it named a node ([confirmedNode]), else this phone's
 * guess. The relays list marks how sure it is beside it ([ConfidenceMark]) and shows the evidence under it.
 */
@Composable
private fun relayText(relayByte: Int, relay: RelayStats?, names: Map<Long, String>): String {
    val byte = relayByteText(relayByte)
    fun name(id: Long) = nodeName(id, names)
    val guess = relay?.guess
    val named = relay?.confirmedNode
    if (named != null) return stringResource(R.string.survey_relay_named, byte, name(named))
    return when (guess) {
        is RelayGuess.Direct -> stringResource(R.string.survey_relay_direct, byte, name(guess.senderId))

        is RelayGuess.Likely,
        is RelayGuess.Possible,
        -> stringResource(R.string.survey_relay_named, byte, name(guess.nodeIds.single()))

        is RelayGuess.Ambiguous -> {
            val shown = guess.nodeIds.take(MAX_RELAY_NAMES).map(::name)
            val more = if (guess.nodeIds.size > MAX_RELAY_NAMES) "…" else ""
            stringResource(
                R.string.survey_relay_named,
                byte,
                shown.reduce { joined, next -> stringResource(R.string.survey_relay_or, joined, next) } + more,
            )
        }

        RelayGuess.Unknown,
        null,
        -> byte
    }
}

/** The node SigSleuth named as this byte's relay, unless the packet came straight from its sender. */
private val RelayStats.confirmedNode: Long?
    get() = server?.nodeId?.takeIf { guess !is RelayGuess.Direct }

/** The single node [relayText] names, if it names one. */
private val RelayStats.shownNode: Long?
    get() = confirmedNode ?: guess.nodeIds.singleOrNull()

private const val MAX_RELAY_NAMES = 3
private const val METERS_PER_KM = 1_000.0
private const val METERS_ROUNDING = 10

private fun nodeName(id: Long, names: Map<Long, String>) = names[id] ?: "!%08x".format(Locale.ROOT, id)

/** A relay byte as it is written everywhere on these screens: "0x3f". */
private fun relayByteText(relayByte: Int) = "0x%02x".format(Locale.ROOT, relayByte)

/**
 * [text] with [relayByte] in the one colour relay bytes have on these screens, and in monospace like a code, so it is
 * easy to find.
 */
@Composable
@ReadOnlyComposable
private fun relayByteMarked(text: String, relayByte: Int): AnnotatedString = highlighted(
    text,
    relayByteText(relayByte),
    SpanStyle(color = MaterialTheme.colorScheme.StatusOrange, fontFamily = FontFamily.Monospace),
)

/** [text] with the first [part] in [style]; unchanged when [part] is not in it. */
private fun highlighted(text: String, part: String, style: SpanStyle): AnnotatedString {
    val at = text.indexOf(part)
    if (at < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(style, at, at + part.length)
    }
}

/**
 * This session's relay bytes, most packets first, each with how sure its guess is, its last packet, and every candidate
 * with how it is known, measured from where that packet was heard: the guess was made there, not where the phone is
 * now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysSheet(state: SurveyState, spreadingFactor: Int, names: Map<Long, String>, onDismiss: () -> Unit) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val now = currentTime()
        LazyColumn(
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.survey_relays_title),
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            val relays = state.relays.entries.sortedByDescending { it.value.packets }
            itemsIndexed(relays, key = { _, entry -> entry.key }) { index, (byte, relay) ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (index > 0) HorizontalDivider()
                    RelayEntry(byte, relay, spreadingFactor, state.neighbours, names, now)
                }
            }
        }
    }
}

/**
 * One relay byte: how sure, who, and when its last packet came, then that packet's signal, SigSleuth's answer and the
 * candidates under the name. The guessed candidates stand out; the rest are muted. Times are measured from [now].
 */
@Composable
@Suppress("LongParameterList")
private fun RelayEntry(
    relayByte: Int,
    relay: RelayStats,
    spreadingFactor: Int,
    neighbours: Map<Long, DirectSender>,
    names: Map<Long, String>,
    now: Long,
) {
    val packet = relay.last
    val guessed = relay.guess.nodeIds.toSet()
    fun tone(nodeId: Long) = if (nodeId in guessed) LineTone.GUESS else LineTone.OTHER
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MARK_GAP)) {
            ConfidenceMark(relay)
            Text(
                relayByteMarked(relayText(relayByte, relay, names), relayByte),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                agoText(relay.lastHeardMs, now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.padding(start = MARK_SIZE + MARK_GAP), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PacketLine(relay, spreadingFactor)
            relay.server?.let { answer ->
                val sameAsHeader = answer.nodeId != null && answer.nodeId == relay.shownNode
                Text(
                    sigSleuthLabelled(serverText(answer, names, sameAsHeader, now)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Every candidate, not just the guessed tier, so the list shows how unsure the guess is.
            relay.candidates.forEach { candidate ->
                val name = nodeName(candidate.nodeId, names)
                val tone = tone(candidate.nodeId)
                when (candidate) {
                    is RelayCandidate.Heard ->
                        CandidateLine(
                            MeshtasticIcons.Rssi,
                            MaterialTheme.colorScheme.tertiary,
                            name,
                            neighbours[candidate.nodeId]?.let { candidateText(it, packet, now) }
                                ?: stringResource(R.string.survey_relay_candidate_heard),
                            tone,
                        )

                    // Its text gives only a distance, so the icon says where the position comes from.
                    is RelayCandidate.Reported ->
                        CandidateLine(
                            MeshtasticIcons.LocationOn,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            name,
                            placedText(candidate, packet),
                            tone,
                            stringResource(R.string.survey_relay_reported_position),
                        )

                    is RelayCandidate.Mapped ->
                        CandidateLine(
                            MeshtasticIcons.Map,
                            MaterialTheme.colorScheme.StatusSky,
                            name,
                            placedText(candidate, packet),
                            tone,
                        )
                }
            }
            // Nodes heard here too weakly to have sent this packet, so the list says why they left.
            relay.ruledOut.forEach { node ->
                CandidateLine(
                    MeshtasticIcons.DoDisturb,
                    MaterialTheme.colorScheme.StatusRed,
                    nodeName(node.nodeId, names),
                    stringResource(
                        R.string.survey_relay_ruled_out,
                        node.packetSignal.roundToInt(),
                        node.strongestHere.roundToInt(),
                    ),
                    LineTone.RULED_OUT,
                )
            }
        }
    }
}

/**
 * How sure the name beside it is: SigSleuth's badge, in its colour, when it named the relay; a green check for a direct
 * packet or this phone's likely guess; an amber question mark when the guess is only possible or ambiguous; and a grey
 * one when no candidate is left. The question mark is the module's own: MeshtasticIcons.HelpOutline draws an "i".
 */
@Composable
private fun ConfidenceMark(relay: RelayStats) {
    val modifier = Modifier.size(MARK_SIZE)
    val colors = MaterialTheme.colorScheme
    val guess = relay.guess
    val unsure = ImageVector.vectorResource(R.drawable.survey_ic_help)
    when {
        relay.confirmedNode != null ->
            Icon(MeshtasticIcons.Verified, stringResource(R.string.survey_relay_confirmed), modifier, colors.StatusSky)

        // The name beside it already says "direct".
        guess is RelayGuess.Direct -> Icon(MeshtasticIcons.CheckCircle, null, modifier, colors.StatusGreen)

        guess is RelayGuess.Likely ->
            Icon(
                MeshtasticIcons.CheckCircle,
                stringResource(R.string.survey_relay_likely),
                modifier,
                colors.StatusGreen,
            )

        guess is RelayGuess.Possible || guess is RelayGuess.Ambiguous ->
            Icon(unsure, stringResource(R.string.survey_relay_uncertain), modifier, colors.StatusYellow)

        else ->
            Icon(
                unsure,
                stringResource(R.string.survey_relay_unknown),
                modifier,
                colors.onSurfaceVariant,
            )
    }
}

/**
 * The packet count, then the last packet's RSSI and SNR when the radio gave them, the SNR as a chip like the "Last
 * packet" line's.
 */
@Composable
private fun PacketLine(relay: RelayStats, spreadingFactor: Int) {
    val count = pluralStringResource(R.plurals.survey_relay_packets, relay.packets, relay.packets)
    val rssi = relay.last.rxRssi?.takeIf { it != 0 }
    val snr = relay.last.rxSnr
    val text =
        when {
            rssi == null -> count
            snr == null -> stringResource(R.string.survey_relay_rssi, count, rssi)
            else -> stringResource(R.string.survey_relay_signal, count, rssi, SNR_SLOT)
        }
    SnrText(
        text,
        snr.takeIf { rssi != null },
        SNR_DECIMALS_LIST,
        spreadingFactor,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** [text] with its "SigSleuth" in SigSleuth's colour, so the answer reads as SigSleuth's without colouring the line. */
@Composable
@ReadOnlyComposable
private fun sigSleuthLabelled(text: String): AnnotatedString =
    highlighted(text, SIGSLEUTH, SpanStyle(color = MaterialTheme.colorScheme.StatusSky))

private const val SIGSLEUTH = "SigSleuth"

/** How much a candidate line stands out: the guess, another candidate, or one the packet's signal ruled out. */
private enum class LineTone {
    GUESS,
    OTHER,
    RULED_OUT,
}

/**
 * A candidate's kind as an icon in [iconTint], the colour of what the knowledge comes from (your radio, SigSleuth, the
 * node's own claim, or red when ruled out), then its short name and how it is known or why it was ruled out, toned by
 * [tone]. [kind] describes the icon for screen readers when [detail] does not already say it.
 */
@Composable
private fun CandidateLine(
    icon: ImageVector,
    iconTint: Color,
    name: String,
    detail: String,
    tone: LineTone,
    kind: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val color =
        when (tone) {
            LineTone.GUESS -> LocalContentColor.current
            LineTone.OTHER -> colors.onSurfaceVariant
            LineTone.RULED_OUT -> colors.outline
        }
    val weight = if (tone == LineTone.GUESS) FontWeight.SemiBold else null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, kind, Modifier.size(LINE_ICON_SIZE), tint = iconTint)
        Text(
            name,
            Modifier.widthIn(min = 48.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = weight,
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private val MARK_SIZE = 20.dp
private val MARK_GAP = 8.dp
private val LINE_ICON_SIZE = 16.dp

/**
 * Distance from where [packet] was heard to the nearest cell where [sender] was heard directly, and when that cell was
 * heard; for a packet without a fix, the latest direct time. A later direct reception elsewhere is left out to keep the
 * line short.
 */
@Composable
private fun candidateText(sender: DirectSender, packet: SurveyMeasurement, now: Long): String {
    val latitude = packet.latitude
    val longitude = packet.longitude
    val nearest =
        if (latitude == null || longitude == null) {
            null
        } else {
            sender.nearest(latitude, longitude, packet.heardAtMs - DIRECT_HISTORY.inWholeMilliseconds)
        }
            ?: return stringResource(
                R.string.survey_relay_candidate,
                agoText(sender.lastDirectMs, now),
            )
    return stringResource(
        R.string.survey_relay_candidate_at,
        distanceText(nearest.meters),
        agoText(nearest.heardAtMs, now),
    )
}

/**
 * Distance from where [packet] was heard to where [candidate] is said to be, which is a claim rather than a reception,
 * with its reach when that is what let it in from farther than [FAR].
 */
@Composable
@ReadOnlyComposable
private fun placedText(candidate: RelayCandidate.Placed, packet: SurveyMeasurement): String {
    val mapped = candidate is RelayCandidate.Mapped
    val latitude = packet.latitude
    val longitude = packet.longitude
    if (latitude == null || longitude == null) {
        return stringResource(
            if (mapped) R.string.survey_relay_candidate_mapped else R.string.survey_relay_candidate_reported,
            distanceText(candidate.withinMeters),
        )
    }
    val meters = latLongToMeter(candidate.latitude, candidate.longitude, latitude, longitude)
    val distance =
        distanceText(meters).let {
            if ((candidate as? RelayCandidate.Mapped)?.estimated == true) {
                stringResource(R.string.survey_distance_about, it)
            } else {
                it
            }
        }
    val line =
        stringResource(
            if (mapped) R.string.survey_relay_candidate_mapped_at else R.string.survey_relay_candidate_reported_at,
            distance,
        )
    val reach = candidate.reachMeters?.takeIf { it > FAR }
    return if (reach == null) line else stringResource(R.string.survey_relay_candidate_reach, line, distanceText(reach))
}

/**
 * SigSleuth's answer for one of this byte's uploaded packets, which may be older than the guess above it. A survey
 * link's evidence takes the place of the packet's time. The node's name is left out when [sameAsHeader], as the line
 * above already shows it.
 */
@Composable
private fun serverText(answer: ServerAnswer, names: Map<Long, String>, sameAsHeader: Boolean, now: Long): String {
    val time = agoText(answer.heardAtMs, now)
    val node = answer.nodeId
    if (node == null) {
        return if (answer.candidates.isEmpty()) {
            stringResource(R.string.survey_relay_server_unknown, time)
        } else {
            val candidates =
                answer.candidates.map { candidate ->
                    val name = names[candidate.nodeId] ?: candidate.name ?: nodeName(candidate.nodeId, names)
                    sharedStringResource(
                        Res.string.survey_relay_server_candidate,
                        name,
                        distanceText(candidate.km * METERS_PER_KM),
                    )
                }
            sharedStringResource(Res.string.survey_relay_server_candidates, candidates.joinToString(", "), time)
        }
    }
    val evidence = answer.evidence
    return when {
        evidence == null && sameAsHeader -> stringResource(R.string.survey_relay_server_same, howText(answer.how), time)

        evidence == null ->
            stringResource(R.string.survey_relay_server, nodeName(node, names), howText(answer.how), time)

        sameAsHeader ->
            stringResource(R.string.survey_relay_server_evidence_same, evidenceText(evidence, answer.heardAtMs, now))

        else ->
            stringResource(
                R.string.survey_relay_server_evidence,
                nodeName(node, names),
                evidenceText(evidence, answer.heardAtMs, now),
            )
    }
}

/** How SigSleuth knows the relay, from [ServerAnswer.how]. */
@Composable
@ReadOnlyComposable
private fun howText(how: String): String = when (how) {
    "direct" -> stringResource(R.string.survey_server_direct)
    "survey_link" -> stringResource(R.string.survey_server_survey_link)
    "gateway_link" -> stringResource(R.string.survey_server_gateway_link)
    "sender_link" -> stringResource(R.string.survey_server_sender_link)
    "reported" -> stringResource(R.string.survey_server_reported)
    "only_candidate" -> stringResource(R.string.survey_server_only_candidate)
    else -> how
}

/**
 * A survey link's relay heard directly, timed from now like the rest of the list: "heard directly by you 2.3 km away at
 * -97 dBm, 29 days ago", without the RSSI when SigSleuth sent none. [packetMs] is the uploaded packet's time, which the
 * evidence's offset is measured from.
 */
@Composable
@ReadOnlyComposable
private fun evidenceText(evidence: LinkEvidence, packetMs: Long, now: Long): String {
    val distance = distanceText(evidence.km * METERS_PER_KM)
    val time = agoText(packetMs + evidence.offsetMs, now)
    val rssi = evidence.rssi
    return when {
        rssi == null && evidence.mine -> stringResource(R.string.survey_server_survey_link_mine, distance, time)
        rssi == null -> stringResource(R.string.survey_server_survey_link_other, distance, time)
        evidence.mine -> stringResource(R.string.survey_server_survey_link_mine_rssi, distance, rssi, time)
        else -> stringResource(R.string.survey_server_survey_link_other_rssi, distance, rssi, time)
    }
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

/**
 * How long before [now] [timeMs] was, coarsely: "12 s ago", "3 min ago", "2 h ago", then the system's words ("3 days
 * ago"). The system's alone counts whole minutes, which says "0 min. ago" for the packet just heard.
 */
@Composable
@ReadOnlyComposable
private fun agoText(timeMs: Long, now: Long): String {
    val seconds = ((now - timeMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
    val duration =
        when {
            seconds < SECONDS_PER_MINUTE -> stringResource(R.string.survey_seconds, seconds.toInt())

            seconds < SECONDS_PER_HOUR ->
                stringResource(R.string.survey_minutes, (seconds / SECONDS_PER_MINUTE).toInt())

            seconds < SECONDS_PER_DAY -> stringResource(R.string.survey_hours, (seconds / SECONDS_PER_HOUR).toInt())

            else -> return DateFormatter.formatRelativeTime(timeMs)
        }
    return stringResource(R.string.survey_ago, duration)
}

/**
 * Where uploads go, folded to one line once set: a server is entered once, so the form only opens on Set up or Edit.
 * Its status and Upload now are about [recording], the session recording now; a line counts the [pastWaiting] ended
 * sessions SigSleuth may have but lacks rows of, which Upload all sends while nothing records. [anyUploaded] says
 * whether an ended session was sent, so "Everything is uploaded" is not said of private sessions alone.
 */
@Composable
@Suppress("LongParameterList")
private fun UploadCard(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    pastWaiting: Int,
    anyUploaded: Boolean,
    uploading: Boolean,
    onSave: (SurveyUploadSettings) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onUpload: (String) -> Unit,
    onUploadAll: () -> Unit,
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

                else ->
                    UploadSummary(
                        settings,
                        recording,
                        pastWaiting,
                        anyUploaded,
                        uploading,
                        onAutomatic,
                        onUpload,
                        onUploadAll,
                    )
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun UploadSummary(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    pastWaiting: Int,
    anyUploaded: Boolean,
    uploading: Boolean,
    onAutomatic: (Boolean) -> Unit,
    onUpload: (String) -> Unit,
    onUploadAll: () -> Unit,
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
            error != null ->
                InfoRow(MeshtasticIcons.ErrorOutline, uploadErrorText(error), MaterialTheme.colorScheme.error)

            pending > 0 ->
                InfoRow(
                    MeshtasticIcons.CloudUpload,
                    pluralStringResource(R.plurals.survey_upload_pending, pending, pending),
                )

            pastWaiting == 0 && (recording != null || anyUploaded) ->
                InfoRow(
                    MeshtasticIcons.CheckCircle,
                    stringResource(R.string.survey_upload_done),
                    iconTint = MaterialTheme.colorScheme.StatusGreen,
                )
        }
        if (pastWaiting > 0) {
            InfoRow(
                MeshtasticIcons.CloudUpload,
                pluralStringResource(R.plurals.survey_upload_past_waiting, pastWaiting, pastWaiting),
                iconTint = MaterialTheme.colorScheme.tertiary,
            )
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
        // Upload all sends only sessions already allowed; a private one is sent from its own card, so nothing goes
        // that the user did not pick.
        when {
            recording != null ->
                UploadButton(R.string.survey_upload_now, pending > 0, uploading) { onUpload(recording.session.id) }

            pastWaiting > 0 -> UploadButton(R.string.survey_upload_all, enabled = true, uploading, onUploadAll)
        }
    }
}

/** Shows Uploading… with a spinner during any run, which may be another session's. */
@Composable
private fun UploadButton(@StringRes label: Int, enabled: Boolean, uploading: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled && !uploading, modifier = Modifier.fillMaxWidth()) {
        if (uploading) {
            CircularProgressIndicator(
                Modifier.size(ButtonDefaults.IconSize),
                color = LocalContentColor.current,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.survey_uploading))
        } else {
            Text(stringResource(label))
        }
    }
}

/** Measurements SigSleuth does not have yet. */
private val SurveySessionSummary.pendingCount: Int
    get() = (measurementCount - uploadedCount).coerceAtLeast(0)

/** Allowed to go to SigSleuth but not all there: a run sends it. A private session is never waiting. */
private val SurveySessionSummary.waiting: Boolean
    get() = session.upload && pendingCount > 0

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

/**
 * A session: when, its upload state, what it found, then small details. Its radio is named only when [showRadio], so a
 * list of sessions on one radio does not repeat it. Export and Delete wait in a menu; Upload shows while rows are
 * missing.
 */
@Composable
@Suppress("LongParameterList")
private fun SessionCard(
    summary: SurveySessionSummary,
    recording: Boolean,
    waiting: Boolean,
    deletable: Boolean,
    uploadsOn: Boolean,
    uploading: Boolean,
    showRadio: Boolean,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val session = summary.session
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val canUpload = !recording && uploadsOn && summary.pendingCount > 0
    // A waiting session is outlined in the colour of the Upload card's waiting line, so it shows while scrolling.
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (waiting) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null,
    ) {
        // The bottom row's buttons bring their own touch padding, so the card's is smaller under them.
        Column(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (recording) 16.dp else 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionTitle(session),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                SessionBadge(summary, recording, waiting)
            }
            session.news?.takeIf { it.cells > 0 }?.let { NewsStats(it) }
            UploadProgress(summary, uploadsOn)
            // The details, small and muted under what the session found.
            Column {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                if (showRadio) Text(radioText(session), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(sessionFacts(summary, recording), style = MaterialTheme.typography.bodySmall, color = muted)
                serverText(session)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
            session.lastUploadError?.let {
                Text(
                    uploadErrorText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // A session still recording can be neither exported whole nor deleted, so it has no menu. No session is
            // deleted while one records ([SurveyViewModel.delete]). The menu sits down here, not beside the date, so
            // a long badge never pushes the date onto two lines.
            if (!recording) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canUpload) {
                        // Disabled, not spinning, during a run: the run may be another session's.
                        TextButton(onClick = onUpload, enabled = !uploading) {
                            Text(stringResource(R.string.survey_upload_session))
                        }
                    }
                    SessionMenu(deletable, onExport, onDelete = { confirmDelete = true })
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

/** Export and, while nothing records, Delete: every ended session has them, so they wait behind ⋮. */
@Composable
private fun SessionMenu(deletable: Boolean, onExport: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Pulled right by the button's own padding, so the icon lines up with the card's text.
    Box(Modifier.offset(x = MENU_BUTTON_INSET)) {
        IconButton(onClick = { open = true }) {
            Icon(MeshtasticIcons.More, contentDescription = stringResource(R.string.survey_session_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.survey_export)) },
                onClick = {
                    open = false
                    onExport()
                },
                leadingIcon = { Icon(MeshtasticIcons.FileDownload, contentDescription = null) },
            )
            if (deletable) {
                val error = MaterialTheme.colorScheme.error
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.survey_delete), color = error) },
                    onClick = {
                        open = false
                        onDelete()
                    },
                    leadingIcon = { Icon(MeshtasticIcons.Delete, contentDescription = null, tint = error) },
                )
            }
        }
    }
}

private val MENU_BUTTON_INSET = 12.dp

/**
 * Cells, relays and senders heard directly, each with what was new to this phone under it in green, and under the
 * cells, once SigSleuth has answered, the [mapCells] its map gained. With [onRelays], the relays figure opens their
 * list.
 */
@Composable
private fun NewsStats(
    news: SurveyNews,
    relays: Int = news.relays,
    mapCells: Int? = news.newCells,
    onRelays: (() -> Unit)? = null,
) {
    @Composable
    @ReadOnlyComposable
    fun newIn(cells: Int) = cells
        .takeIf { it > 0 }
        ?.let {
            pluralStringResource(R.plurals.survey_stat_new_in_cells, it, it)
        }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat(
            news.cells.toString(),
            stringResource(R.string.survey_stat_cells),
            Modifier.weight(1f),
            note = news.localNewCells.takeIf { it > 0 }?.let { stringResource(R.string.survey_stat_new, it) },
            mapNote = mapCells?.let { stringResource(R.string.survey_stat_added, it) },
        )
        Stat(
            relays.toString(),
            stringResource(R.string.survey_stat_relays_heard),
            Modifier.weight(1f),
            note = newIn(news.localNewRelayCells),
            onClick = onRelays,
            onClickLabel = stringResource(R.string.survey_relays_open),
        )
        Stat(
            news.direct.toString(),
            stringResource(R.string.survey_stat_direct),
            Modifier.weight(1f),
            note = newIn(news.localNewDirectCells),
        )
    }
}

/**
 * Where a session stands, beside its date: recording, waiting to upload (blue, as its border), or uploaded (green).
 * Nothing for a session that is private or was never sent.
 */
@Composable
private fun SessionBadge(summary: SurveySessionSummary, recording: Boolean, waiting: Boolean) {
    val (icon, text, color) =
        when {
            recording -> Triple(null, R.string.survey_recording, MaterialTheme.colorScheme.primary)

            waiting ->
                Triple(MeshtasticIcons.CloudUpload, R.string.survey_session_waiting, MaterialTheme.colorScheme.tertiary)

            summary.measurementCount > 0 && summary.uploadedCount >= summary.measurementCount ->
                Triple(MeshtasticIcons.CheckCircle, R.string.survey_uploaded_all, MaterialTheme.colorScheme.StatusGreen)

            else -> return
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(INFO_ICON_SIZE), tint = color) }
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** The cells, the senders heard directly and the cells added to the map of every session on this phone. */
@Composable
private fun TotalsCard(totals: SurveyTotals) {
    // The one card in the container colour, with the screen's biggest numbers: the progress across every session.
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(MeshtasticIcons.Map, contentDescription = null)
                Text(stringResource(R.string.survey_totals), style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TotalStat(totals.cells, stringResource(R.string.survey_stat_cells), Modifier.weight(1f))
                TotalStat(totals.direct, stringResource(R.string.survey_stat_direct), Modifier.weight(1f))
                totals.mapCells?.let {
                    TotalStat(it, stringResource(R.string.survey_totals_map), Modifier.weight(1f))
                }
            }
        }
    }
}

/** A [TotalsCard] figure: [Stat]'s layout, larger, in the card's content colour. */
@Composable
private fun TotalStat(value: Int, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}.padding(vertical = 4.dp)) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current.copy(alpha = SECONDARY_ALPHA),
        )
    }
}

private const val SECONDARY_ALPHA = 0.8f

/** When it started and, once ended, how long it ran: "16 Nov 2026 10:02 · 0:07:25". */
private fun sessionTitle(session: SurveySession): String {
    val start = DateFormatter.formatDateTime(session.startedAt)
    val duration = session.endedAt?.let { clockText(it - session.startedAt) } ?: return start
    return "$start · $duration"
}

/** Size and, when it ended on its own, how: "1234 measurements · Ended: app was closed". A Stop goes unsaid. */
@Composable
private fun sessionFacts(summary: SurveySessionSummary, recording: Boolean): String {
    val count = pluralStringResource(R.plurals.survey_measurements, summary.measurementCount, summary.measurementCount)
    if (recording || summary.session.status == SurveyStatus.STOPPED) return count
    return "$count · ${stringResource(summary.session.status.label())}"
}

/**
 * How much SigSleuth has, while some is missing; nothing when nothing was ever sent and uploads are off or the session
 * is private. A fully uploaded session says so beside its date ([SessionBadge]).
 */
@Composable
private fun UploadProgress(summary: SurveySessionSummary, uploadsOn: Boolean) {
    val count = summary.measurementCount
    val uploaded = summary.uploadedCount.coerceAtMost(count)
    val missing = uploaded < count
    val shown = uploaded > 0 || (uploadsOn && summary.session.upload)
    if (!missing || !shown) return
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.survey_uploaded_of, uploaded, count), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { uploaded.toFloat() / count }, modifier = Modifier.fillMaxWidth())
    }
}

/** What SigSleuth kept and, if any, what it skipped and why. */
@Composable
private fun serverText(session: SurveySession): String? {
    if (session.serverKept + session.serverSkipped == 0L) return null
    val reasons =
        SurveyUploader.decodeSkipReasons(session.serverSkipReasons).entries.map { (reason, count) ->
            skipReasonText(reason, count)
        }
    return when {
        session.serverSkipped == 0L -> stringResource(R.string.survey_server_kept_all, session.serverKept)

        reasons.isEmpty() -> stringResource(R.string.survey_server_kept, session.serverKept, session.serverSkipped)

        else ->
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
