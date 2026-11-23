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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.meshtastic.core.common.util.DateFormatter
import org.meshtastic.core.common.util.NumberFormatter
import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.survey_relay_server_candidate
import org.meshtastic.core.resources.survey_relay_server_candidates
import org.meshtastic.core.ui.component.EmptyState
import org.meshtastic.core.ui.component.Quality
import org.meshtastic.core.ui.component.RSSI_BAD_THRESHOLD
import org.meshtastic.core.ui.component.RSSI_FAIR_THRESHOLD
import org.meshtastic.core.ui.component.RSSI_GOOD_THRESHOLD
import org.meshtastic.core.ui.icon.ArrowBack
import org.meshtastic.core.ui.icon.BarChart
import org.meshtastic.core.ui.icon.Check
import org.meshtastic.core.ui.icon.CheckCircle
import org.meshtastic.core.ui.icon.ChevronRight
import org.meshtastic.core.ui.icon.CloudUpload
import org.meshtastic.core.ui.icon.Delete
import org.meshtastic.core.ui.icon.DoDisturb
import org.meshtastic.core.ui.icon.Edit
import org.meshtastic.core.ui.icon.ErrorOutline
import org.meshtastic.core.ui.icon.FileDownload
import org.meshtastic.core.ui.icon.History
import org.meshtastic.core.ui.icon.Info
import org.meshtastic.core.ui.icon.LinkOff
import org.meshtastic.core.ui.icon.LocationDisabled
import org.meshtastic.core.ui.icon.LocationOn
import org.meshtastic.core.ui.icon.Map
import org.meshtastic.core.ui.icon.MeshtasticIcons
import org.meshtastic.core.ui.icon.More
import org.meshtastic.core.ui.icon.MqttDelivered
import org.meshtastic.core.ui.icon.MyLocation
import org.meshtastic.core.ui.icon.PlayArrow
import org.meshtastic.core.ui.icon.Route
import org.meshtastic.core.ui.icon.Rssi
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
import org.meshtastic.feature.survey.SurveyDeleted
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
import org.meshtastic.feature.survey.uploadUrlFrom
import org.meshtastic.feature.survey.uploadUrlTyped
import java.util.Locale
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource as sharedStringResource

/** What the survey screen shows, as [SurveyViewModel]'s flows have it; [SurveyContent] draws it. */
internal data class SurveyUi(
    val state: SurveyState = SurveyState(),
    val radioConnected: Boolean = false,
    val relayNames: Map<Long, String> = emptyMap(),
    val sessions: List<SurveySessionSummary> = emptyList(),
    val uploadSettings: SurveyUploadSettings? = null,
    val uploading: Boolean = false,
    val carMode: Boolean = false,
    val deleted: SurveyDeleted? = null,
    val directSenders: Int = 0,
    val opened: OpenedSession? = null,
)

/** What the survey screen's controls do. [SurveyScreen] hands them to [SurveyViewModel]; by default they do nothing. */
@Suppress("LongParameterList")
internal class SurveyActions(
    val onNavigateUp: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onCarMode: (Boolean) -> Unit = {},
    val onSaveServer: (SurveyUploadSettings) -> Unit = {},
    val onAutomatic: (Boolean) -> Unit = {},
    val onUpload: (sessionId: String) -> Unit = {},
    val onUploadAll: () -> Unit = {},
    val onExport: (SurveySessionSummary) -> Unit = {},
    val onDelete: (sessionId: String) -> Unit = {},
    val onOpen: (sessionId: String) -> Unit = {},
    val onCloseSession: () -> Unit = {},
)

/**
 * A still picture of the survey screen, for screenshots: every clock stopped at [now], and the relay rows of
 * [openRelays] opened. Null, as in the app, the clocks tick and every row starts closed.
 */
internal data class SurveySnapshot(val now: SurveyNow, val openRelays: Set<Int> = emptySet())

// A composition local so the clocks deep in the cards and rows stop without threading a parameter through them all.
@Suppress("CompositionLocalAllowlist")
internal val LocalSurveySnapshot = staticCompositionLocalOf<SurveySnapshot?> { null }

@Composable
fun SurveyScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SurveyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val radioConnected by viewModel.radioConnected.collectAsStateWithLifecycle()
    val relayNames by viewModel.relayNames.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val uploadSettings by viewModel.uploadSettings.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val carMode by viewModel.carMode.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val directSenders by viewModel.directSenders.collectAsStateWithLifecycle()
    val opened by viewModel.opened.collectAsStateWithLifecycle()
    // Only while this screen is in front: Android drops the flag once the app is in the background.
    KeepScreenOn(carMode && state.session != null)

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
    val actions =
        remember(viewModel, onNavigateUp) {
            SurveyActions(
                onNavigateUp = onNavigateUp,
                onStart = requestStart,
                onStop = viewModel::stop,
                onCarMode = viewModel::setCarMode,
                onSaveServer = viewModel::saveServer,
                onAutomatic = viewModel::setAutomatic,
                onUpload = viewModel::upload,
                onUploadAll = viewModel::uploadAll,
                onExport = { summary ->
                    exportSessionId = summary.session.id
                    exporter.launch(csvFileName(summary))
                },
                onDelete = viewModel::delete,
                onOpen = viewModel::open,
                onCloseSession = viewModel::closeSession,
            )
        }
    SurveyContent(
        SurveyUi(
            state,
            radioConnected,
            relayNames,
            sessions,
            uploadSettings,
            uploading,
            carMode,
            deleted,
            directSenders,
            opened,
        ),
        actions,
        modifier,
    )
}

/** The survey screen drawn from [ui] alone, so screenshots can draw it from made-up data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SurveyContent(ui: SurveyUi, actions: SurveyActions, modifier: Modifier = Modifier) {
    val state = ui.state
    val sessions = ui.sessions
    val uploadSettings = ui.uploadSettings
    val uploading = ui.uploading
    val totals =
        remember(sessions, ui.deleted, ui.directSenders) {
            surveyTotals(sessions.map { it.session }, ui.deleted, ui.directSenders)
        }
    val uploadsOn = uploadSettings?.url?.isNotBlank() == true
    val recording = state.session
    // The session recording now has the screen to itself, so the list holds only ended ones.
    val past = remember(sessions, recording?.id) { sessions.filter { it.session.id != recording?.id } }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.survey_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = actions.onNavigateUp) {
                        Icon(MeshtasticIcons.ArrowBack, contentDescription = stringResource(R.string.survey_back))
                    }
                },
                actions = { CarModeChip(ui.carMode, actions.onCarMode) },
            )
        },
    ) { padding ->
        if (recording != null) {
            val live = sessions.firstOrNull { it.session.id == recording.id }
            // Per session, so the next one does not open on it.
            var showUpload by rememberSaveable(recording.id) { mutableStateOf(false) }
            RecordingView(
                state,
                recording,
                ui.relayNames,
                car = ui.carMode,
                upload = uploadSettings?.takeIf { uploadsOn }?.let { uploadMark(it, live) },
                onUpload = { showUpload = true },
                onStop = actions.onStop,
                modifier = Modifier.padding(padding),
            )
            if (showUpload && uploadSettings != null) {
                UploadSheet(
                    uploadSettings,
                    live,
                    pastWaiting = past.count { it.waiting },
                    uploading = uploading,
                    onAutomatic = actions.onAutomatic,
                    onUpload = { actions.onUpload(recording.id) },
                    onDismiss = { showUpload = false },
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "idle") { IdleCard(state, ui.radioConnected, onStart = actions.onStart) }
                uploadSettings?.let { current ->
                    item(key = "upload") {
                        UploadCard(
                            current,
                            pastWaiting = past.count { it.waiting },
                            anyUploaded = past.any { it.uploadedCount > 0 },
                            uploading = uploading,
                            onSave = actions.onSaveServer,
                            onAutomatic = actions.onAutomatic,
                            onUploadAll = actions.onUploadAll,
                        )
                    }
                }
                // The lifetime progress, apart from the list.
                totals?.let { item(key = "totals") { TotalsCard(it) } }
                item(key = "sessions") {
                    Text(
                        stringResource(R.string.survey_sessions),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (past.isEmpty()) {
                    item(key = "no_sessions") {
                        EmptyState(
                            icon = MeshtasticIcons.History,
                            title = stringResource(R.string.survey_no_sessions),
                            supportingText = stringResource(R.string.survey_no_sessions_hint),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                itemsIndexed(past, key = { _, summary -> summary.session.id }) { index, summary ->
                    // Newest first: a card names its radio when it differs from the session before it, the oldest
                    // always.
                    val older = past.getOrNull(index + 1)?.session
                    SessionCard(
                        summary = summary,
                        waiting = uploadsOn && summary.waiting,
                        uploadsOn = uploadsOn,
                        uploading = uploading,
                        showRadio = older == null || radioKey(older) != radioKey(summary.session),
                        onOpen = { actions.onOpen(summary.session.id) }.takeIf { summary.measurementCount > 0 },
                        onUpload = { actions.onUpload(summary.session.id) },
                        onExport = { actions.onExport(summary) },
                        onDelete = { actions.onDelete(summary.session.id) },
                    )
                }
            }
        }
        ui.opened?.let { SessionRelaysSheet(it, onDismiss = actions.onCloseSession) }
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

/** Before a session: what the survey does, a warning while no radio is connected, why the last one ended, and Start. */
@Composable
private fun IdleCard(state: SurveyState, radioConnected: Boolean, onStart: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.survey_not_recording), style = MaterialTheme.typography.titleMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.survey_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!radioConnected) InfoRow(MeshtasticIcons.LinkOff, stringResource(R.string.survey_radio_none))
                state.lastEnd?.endedNotice()?.let { InfoRow(MeshtasticIcons.Info, it) }
            }
            state.problem?.let { ProblemBanner(it) }
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    MeshtasticIcons.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.survey_start))
            }
        }
    }
}

/**
 * The screen while a session records: its card over its relays, which scroll on their own so the card stays in view.
 * When the height cannot hold the card and a few rows, as with the phone on its side, the card scrolls with them. In
 * [car] mode everything is [CAR_SCALE] times bigger and the small text is left out, to read at a glance.
 */
@Composable
@Suppress("LongParameterList")
private fun RecordingView(
    state: SurveyState,
    session: SurveySession,
    relayNames: Map<Long, String>,
    car: Boolean,
    upload: UploadMark?,
    onUpload: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One clock for the card and the list, so the timer and every "ago" tick in step.
    val clockState = rememberSurveyClock()
    val clock = clockState::value
    val density = LocalDensity.current
    // Bigger through the density, so text, icons, gaps and touch targets grow together from the same layout.
    val scaled = if (car) Density(density.density * CAR_SCALE, density.fontScale) else density
    // At CAR_SCALE, 34 dp is already ~48 real dp; keep touch targets compliant without inflating header/button air.
    val minTouch = if (car) CAR_TOUCH_TARGET else LocalMinimumInteractiveComponentSize.current
    val horizontalEdge = if (car) 12.dp else 16.dp
    val verticalEdge = if (car) 8.dp else 16.dp
    // The Scaffold's padding is measured at the screen's own density, outside the scaled part.
    Box(modifier) {
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalMinimumInteractiveComponentSize provides minTouch,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val card: @Composable () -> Unit = {
                    RecordingCard(state, session, relayNames, clock, car, upload, onUpload, onStop)
                }
                val scrolledCard: @Composable () -> Unit = {
                    Box(Modifier.padding(bottom = if (car) 8.dp else 12.dp)) { card() }
                }
                val pinned = maxHeight >= PINNED_MIN_HEIGHT
                Column {
                    if (pinned) {
                        Box(Modifier.padding(horizontal = horizontalEdge, vertical = verticalEdge)) { card() }
                    }
                    RelaysList(
                        state.relays,
                        state.neighbours,
                        session.spreadingFactor,
                        relayNames,
                        clock,
                        Modifier.weight(1f),
                        contentPadding =
                        PaddingValues(
                            start = horizontalEdge,
                            end = horizontalEdge,
                            top = if (pinned) 0.dp else verticalEdge,
                            bottom = if (car) 12.dp else 16.dp,
                        ),
                        lastRelayByte = state.lastMeasurement?.relayByte,
                        car = car,
                        header = scrolledCard.takeUnless { pinned },
                    )
                }
            }
        }
    }
}

/** How much bigger car mode draws the recording view. */
private const val CAR_SCALE = 1.4f

/** Scaled touch target in car mode: 34 dp × 1.4 = 47.6 real dp, meeting M3's 48 dp target without 67 dp dead air. */
private val CAR_TOUCH_TARGET = 34.dp
private val CAR_STOP_HEIGHT = 36.dp

/** The height, in the view's own units, below which the recording card scrolls with the relays. */
private val PINNED_MIN_HEIGHT = 420.dp

/**
 * Car mode, in the top bar so it stays in view in every state: while recording it keeps the screen on and draws the
 * recording view bigger, without its small text. A chip rather than an icon, so its words say what it does. On, it is
 * filled in the colour of a switch that is on and checked; off, only outlined.
 */
@Composable
private fun CarModeChip(carMode: Boolean, onCarMode: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = carMode,
        onClick = { onCarMode(!carMode) },
        label = { Text(stringResource(R.string.survey_car_mode), maxLines = 1) },
        modifier = Modifier.padding(end = 8.dp),
        leadingIcon =
        if (carMode) {
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

/**
 * The session recording now: what it is doing, then what it found, the last packet and Stop. Its relays are in the list
 * under it. In [car] mode the small text is left out: Packets, the notes under the figures but the new cells.
 */
@Composable
@Suppress("LongParameterList")
private fun RecordingCard(
    state: SurveyState,
    session: SurveySession,
    relayNames: Map<Long, String>,
    clock: () -> SurveyNow,
    car: Boolean,
    upload: UploadMark?,
    onUpload: () -> Unit,
    onStop: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(if (car) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (car) 8.dp else 12.dp),
        ) {
            RecordingHeader(state, session, clock, car, upload, onUpload)
            if (state.waitingForRadio) {
                Text(
                    stringResource(R.string.survey_waiting_explained, SurveyRecorder.RADIO_WAIT.inWholeMinutes.toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // The figures its session card shows once it ends. Relays counts every byte heard, fix or not, matching
            // the list under the card and the ended session's sheet.
            NewsStats(
                state.newToYou ?: NO_NEWS,
                relays = state.relays.size,
                mapCells = state.news?.newCells.takeUnless { car },
                packets = state.measurementCount.takeUnless { car },
                car = car,
            )
            LastPacket(
                last = state.lastMeasurement,
                relay = state.lastMeasurement?.relayByte?.let(state.relays::get),
                relayNames = relayNames,
                spreadingFactor = session.spreadingFactor,
                clock = clock,
                car = car,
            )
            state.problem?.let { ProblemBanner(it) }
            Button(
                onClick = onStop,
                modifier = if (car) Modifier.fillMaxWidth().height(CAR_STOP_HEIGHT) else Modifier.fillMaxWidth(),
                contentPadding =
                if (car) PaddingValues(horizontal = 16.dp, vertical = 0.dp) else ButtonDefaults.ContentPadding,
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

/**
 * A dot in the colour of what the session is doing and its words, the GPS and upload marks, and how long it has run. In
 * [car] mode the running time takes the words' place; while the radio is away the words stay and GPS, paused on
 * purpose, is left out.
 */
@Composable
@Suppress("LongParameterList")
private fun RecordingHeader(
    state: SurveyState,
    session: SurveySession,
    clock: () -> SurveyNow,
    car: Boolean,
    upload: UploadMark?,
    onUpload: () -> Unit,
) {
    val waiting = state.waitingForRadio
    val colors = MaterialTheme.colorScheme
    val time = clockText(clock().wallMs - session.startedAt)
    val timeStyle = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (waiting) colors.StatusYellow else colors.StatusGreen)
        if (car && !waiting) {
            FitText(time, timeStyle, Modifier.weight(1f))
        } else {
            Text(
                stringResource(if (waiting) R.string.survey_waiting_title else R.string.survey_recording),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!waiting) GpsMark(state.fix, clock)
        upload?.let { UploadMarkButton(it, onUpload, car) }
        if (!car) Text(time, style = timeStyle, maxLines = 1)
    }
}

@Composable private fun StatusDot(color: Color) = Box(Modifier.size(10.dp).background(color, CircleShape))

private val NO_NEWS = SurveyNews(cells = 0, relays = 0, direct = 0)

/**
 * The last packet in an inset surface so it reads apart from the session's running totals: it pulses briefly in the
 * packet's fade-margin colour whenever a newer packet arrives, and dims its signal figures once it goes stale.
 *
 * Normal mode shows the resolved relay (matching the list below) with its age on the right, then SNR chip, RSSI and a
 * muted no-GPS mark when the packet could not be placed. Car mode strips labels and promotes the SNR chip so neither
 * line wraps at [CAR_SCALE].
 */
@Composable
@Suppress("LongParameterList")
private fun LastPacket(
    last: SurveyMeasurement?,
    relay: RelayStats?,
    relayNames: Map<Long, String>,
    spreadingFactor: Int,
    clock: () -> SurveyNow,
    car: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val now = clock().wallMs
    val ageMs = last?.let { (now - it.heardAtMs).coerceAtLeast(0L) }
    val stale = ageMs != null && ageMs >= STALE_PACKET_MS
    val signalAlpha by animateFloatAsState(if (stale) STALE_SIGNAL_ALPHA else 1f, label = "stale")

    // Pulse once when a newer packet arrives, never on first composition or a car-mode toggle.
    val pulse = remember { Animatable(0f) }
    var seenSeq by remember { mutableStateOf(last?.seq) }
    LaunchedEffect(last?.seq) {
        val seq = last?.seq ?: return@LaunchedEffect
        if (seq != seenSeq) {
            seenSeq = seq
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = if (car) CAR_PULSE_MS else PACKET_PULSE_MS))
        }
    }

    val pulseTint = last?.rxSnr?.let { marginColor(fadeMargin(it, spreadingFactor)) } ?: colors.primary
    val fillAlpha = if (car) CAR_PULSE_FILL_ALPHA else PACKET_PULSE_FILL_ALPHA
    val containerColor = lerp(colors.surfaceContainerLow, pulseTint, pulse.value * fillAlpha)
    val borderColor =
        lerp(colors.outlineVariant.copy(alpha = REST_BORDER_ALPHA), pulseTint, pulse.value * PULSE_BORDER_ALPHA)
    val lastPacketPrefix = stringResource(R.string.survey_last_packet)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        val verticalPadding = if (car) 8.dp else 10.dp
        if (last == null) {
            Text(
                stringResource(R.string.survey_no_packet),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = verticalPadding),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            return@Surface
        }
        Column(
            modifier =
            Modifier.padding(horizontal = 12.dp, vertical = verticalPadding).semantics(mergeDescendants = true) {
                if (last.relayByte != null) stateDescription = lastPacketPrefix
            },
            verticalArrangement = Arrangement.spacedBy(if (car) 4.dp else 6.dp),
        ) {
            LastPacketHeader(last, relay, relayNames, now, car)
            LastPacketSignal(last, spreadingFactor, car, Modifier.alpha(signalAlpha))
        }
    }
}

@Composable
private fun LastPacketHeader(
    last: SurveyMeasurement,
    relay: RelayStats?,
    relayNames: Map<Long, String>,
    now: Long,
    car: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val relayByte = last.relayByte
    val age = if (car) shortAgeText(last.heardAtMs, now) else agoText(last.heardAtMs, now)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (!car && relay != null) ConfidenceMark(relay, size = LAST_PACKET_MARK_SIZE)
        val label =
            when {
                relayByte == null -> AnnotatedString(stringResource(R.string.survey_last_packet))
                car -> relayByteMarked(carRelayText(relayByte, relay, relayNames), relayByte)
                else -> relayByteMarked(relayText(relayByte, relay, relayNames), relayByte)
            }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = if (relayByte == null) colors.onSurfaceVariant else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            age,
            style =
            (if (car) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium).copy(
                fontFeatureSettings = "tnum",
            ),
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LastPacketSignal(
    last: SurveyMeasurement,
    spreadingFactor: Int,
    car: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val snr = last.rxSnr
    val rssi = last.rxRssi?.takeIf { it != 0 }
    if (car) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (snr != null) {
                SnrChip(
                    snr = snr,
                    spreadingFactor = spreadingFactor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    padding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
                    shape = MaterialTheme.shapes.small,
                )
            } else {
                Text("–", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
            }
            RssiReading(rssi, style = MaterialTheme.typography.titleSmall)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.survey_snr_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                if (snr != null) {
                    SnrChip(snr, spreadingFactor, style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("–", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Text("·", style = MaterialTheme.typography.bodyMedium, color = colors.outline)
                Text(
                    stringResource(R.string.survey_rssi_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                RssiReading(rssi, style = MaterialTheme.typography.bodyMedium)
            }
            if (last.cellX == null) {
                Icon(
                    MeshtasticIcons.LocationDisabled,
                    contentDescription = stringResource(R.string.survey_last_no_gps),
                    modifier = Modifier.size(LINE_ICON_SIZE),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** An RSSI reading in tabular figures beside a dot in its quality band, keeping high contrast on tonal surfaces. */
@Composable
private fun RssiReading(rssi: Int?, style: TextStyle) {
    val colors = MaterialTheme.colorScheme
    if (rssi == null) {
        Text("–", style = style, color = colors.onSurfaceVariant, maxLines = 1)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(RSSI_DOT_SIZE).background(rssiQuality(rssi).color(), CircleShape))
        FitText(
            stringResource(R.string.survey_rssi_value, rssi),
            style.copy(fontFeatureSettings = "tnum"),
            color = colors.onSurface,
        )
    }
}

private const val STALE_PACKET_MS = 60_000L
private const val STALE_SIGNAL_ALPHA = 0.55f
private const val PACKET_PULSE_MS = 700
private const val CAR_PULSE_MS = 900
private const val PACKET_PULSE_FILL_ALPHA = 0.18f
private const val CAR_PULSE_FILL_ALPHA = 0.28f
private const val REST_BORDER_ALPHA = 0.5f
private const val PULSE_BORDER_ALPHA = 0.85f
private val LAST_PACKET_MARK_SIZE = 18.dp
private val RSSI_DOT_SIZE = 7.dp

/**
 * One figure with its [FigureLabel] under it, then the [note] of what was new to this phone in green and the [mapNote]
 * of what SigSleuth's map gained in its colour. With [onClick], the figure and its label are a filled chip; every stat
 * is inset by [STAT_INSET] so their figures line up whether or not one is a chip.
 */
@Composable
@Suppress("LongParameterList")
private fun Stat(
    value: String,
    figure: Figure,
    modifier: Modifier = Modifier,
    inlineNote: String? = null,
    note: String? = null,
    mapNote: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val chip =
        if (onClick == null) {
            Modifier
        } else {
            Modifier.clip(MaterialTheme.shapes.small)
                .background(colors.primaryContainer)
                .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
        }
    val content = if (onClick == null) Color.Unspecified else colors.onPrimaryContainer
    Column(if (onClick == null) modifier.semantics(mergeDescendants = true) {} else modifier) {
        Column(chip.padding(horizontal = STAT_INSET, vertical = if (onClick == null) 2.dp else 4.dp)) {
            // An inline note that does not fit beside the figure goes under it, rather than shrinking to "…".
            FlowRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = if (inlineNote != null) Modifier.alignByBaseline() else Modifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    // Weighted, so the chevron is measured first and the figure shrinks to the room left.
                    FitText(
                        value,
                        MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                        Modifier.weight(1f, fill = false),
                        color = content,
                    )
                    if (onClick != null) {
                        Icon(
                            MeshtasticIcons.ChevronRight,
                            contentDescription = null,
                            Modifier.size(20.dp),
                            tint = content,
                        )
                    }
                }
                inlineNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        color = colors.StatusGreen,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            // In the chip, its icon takes the chip's colour: the figure's own has too little contrast on it.
            if (onClick == null) FigureLabel(figure) else FigureLabel(figure, content, content)
        }
        val notes = Modifier.padding(horizontal = STAT_INSET)
        note?.let { Text(it, notes, style = MaterialTheme.typography.labelMedium, color = colors.StatusGreen) }
        mapNote?.let { Text(it, notes, style = MaterialTheme.typography.labelMedium, color = colors.StatusSky) }
    }
}

/** How far a [Stat]'s text sits inside its chip, and how far a row of them reaches past its parent on each side. */
private val STAT_INSET = 8.dp

/**
 * What a card's figure counts, with its label and the icon and colour it has on every card: cells in a grid, relays in
 * the relay byte's orange, senders heard directly in the colour of what your radio heard (as in the relays list), and
 * what SigSleuth's map gained in SigSleuth's.
 */
private enum class Figure(@StringRes val label: Int) {
    CELLS(R.string.survey_stat_cells),
    RELAYS(R.string.survey_stat_relays_heard),
    DIRECT(R.string.survey_stat_direct),
    PACKETS(R.string.survey_stat_packets),
    MAP(R.string.survey_totals_map),
}

@Composable
private fun Figure.icon(): ImageVector = when (this) {
    Figure.CELLS -> ImageVector.vectorResource(R.drawable.survey_ic_grid)
    Figure.RELAYS -> MeshtasticIcons.Route
    Figure.DIRECT -> MeshtasticIcons.Rssi
    Figure.PACKETS -> MeshtasticIcons.BarChart
    Figure.MAP -> MeshtasticIcons.Map
}

@Composable
@ReadOnlyComposable
private fun Figure.color(): Color = when (this) {
    Figure.RELAYS -> MaterialTheme.colorScheme.StatusOrange

    Figure.DIRECT -> MaterialTheme.colorScheme.tertiary

    Figure.MAP -> MaterialTheme.colorScheme.StatusSky

    Figure.CELLS,
    Figure.PACKETS,
    -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A figure's label after its icon, the icon in [iconTint] (the figure's colour) and the words in [color]. */
@Composable
private fun FigureLabel(
    figure: Figure,
    iconTint: Color = figure.color(),
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(figure.icon(), contentDescription = null, Modifier.size(FIGURE_ICON_SIZE), tint = iconTint)
        Text(stringResource(figure.label), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private val FIGURE_ICON_SIZE = 14.dp

/** One line of [text] that shrinks to fit its width, down to [FIT_MIN_SIZE], instead of losing characters. */
@Composable
private fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) =
    Text(
        text,
        modifier,
        color = color,
        autoSize = TextAutoSize.StepBased(minFontSize = FIT_MIN_SIZE, maxFontSize = style.fontSize),
        style = style,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

private val FIT_MIN_SIZE = 10.sp

/** Widens the content by [x] on each side, so text inset by [x] lines up with the parent's. */
private fun Modifier.bleed(x: Dp) = layout { measurable, constraints ->
    val extra = (x * 2).roundToPx()
    val placeable =
        measurable.measure(
            constraints.copy(
                minWidth = (constraints.minWidth + extra).coerceAtMost(constraints.maxWidth + extra),
                maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
            ),
        )
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-x.roundToPx(), 0) }
}

/**
 * "GPS", ok or not as SigSleuth sees it: ok while the fix is fresh and close enough to place a packet, else crossed out
 * in the error colour, as packets heard then are skipped. A tap shows the fix's accuracy and age, and SigSleuth's
 * limits when they are not met.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsMark(fix: SurveyFix?, clock: () -> SurveyNow) {
    val ageMs = fix?.let { (clock().elapsedMs - it.elapsedRealtimeMs).coerceAtLeast(0) }
    val usable = fix != null && ageMs != null && usableFix(ageMs, fix.accuracyM)
    val colors = MaterialTheme.colorScheme
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val stateText = stringResource(if (usable) R.string.survey_gps_usable else R.string.survey_gps_unusable)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            RichTooltip {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (fix == null || ageMs == null) {
                            stringResource(R.string.survey_gps_none)
                        } else {
                            stringResource(
                                R.string.survey_gps,
                                fix.accuracyM?.let { NumberFormatter.format(it, 0) } ?: "?",
                                (ageMs / MILLIS_PER_SECOND).toString(),
                            )
                        },
                    )
                    if (!usable) {
                        Text(
                            stringResource(
                                R.string.survey_gps_skipped,
                                SIGSLEUTH_MAX_FIX_AGE_S,
                                SIGSLEUTH_MAX_ACCURACY_M,
                            ),
                        )
                    }
                }
            }
        },
        state = tooltip,
    ) {
        Row(
            Modifier.minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { scope.launch { tooltip.show() } }
                .semantics { stateDescription = stateText }
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (usable) MeshtasticIcons.MyLocation else MeshtasticIcons.LocationDisabled,
                contentDescription = null,
                modifier = Modifier.size(INFO_ICON_SIZE),
                tint = if (usable) colors.StatusGreen else colors.error,
            )
            Text(
                stringResource(R.string.survey_gps_label),
                style = MaterialTheme.typography.labelLarge,
                color = if (usable) colors.onSurfaceVariant else colors.error,
            )
        }
    }
}

private fun usableFix(ageMs: Long, accuracyM: Float?): Boolean =
    ageMs <= SIGSLEUTH_MAX_FIX_AGE_S * MILLIS_PER_SECOND && (accuracyM ?: 0f) <= SIGSLEUTH_MAX_ACCURACY_M

/** What the header's cloud says about uploads while recording, when a server is set. */
private enum class UploadMark {
    /** Uploads run by themselves, or nothing waits. */
    FINE,

    /** Measurements wait, and only Upload now sends them. */
    WAITING,

    /** Measurements wait, and the last upload failed. */
    FAILED,
}

private fun uploadMark(settings: SurveyUploadSettings, recording: SurveySessionSummary?): UploadMark {
    val pending = recording?.pendingCount ?: 0
    return when {
        pending > 0 && recording?.session?.lastUploadError != null -> UploadMark.FAILED
        pending > 0 && !settings.automatic -> UploadMark.WAITING
        else -> UploadMark.FINE
    }
}

/** The upload card's place while recording: a cloud, a waiting one in the waiting colour, or an error mark. */
@Composable
private fun UploadMarkButton(mark: UploadMark, onClick: () -> Unit, car: Boolean) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint, description) =
        when (mark) {
            UploadMark.FINE -> Triple(MeshtasticIcons.CloudUpload, colors.onSurfaceVariant, R.string.survey_upload)

            UploadMark.WAITING ->
                Triple(MeshtasticIcons.CloudUpload, colors.tertiary, R.string.survey_upload_mark_waiting)

            UploadMark.FAILED -> Triple(MeshtasticIcons.ErrorOutline, colors.error, R.string.survey_upload_mark_failed)
        }
    if (car) {
        Box(
            Modifier.size(CAR_TOUCH_TARGET).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = stringResource(description), tint = tint)
        }
    } else {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(description), tint = tint) }
    }
}

private val INFO_ICON_SIZE = 18.dp
private val INFO_ICON_GAP = 8.dp

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
 * [snr] and its unit drawn as a chip on roughly the colour SigSleuth's map gives this spot (its fade margin), in black
 * or white, whichever reads better.
 */
@Composable
private fun SnrChip(
    snr: Float,
    spreadingFactor: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    padding: PaddingValues = PaddingValues(horizontal = SNR_CHIP_PADDING, vertical = 2.dp),
    shape: Shape = MaterialTheme.shapes.extraSmall,
) {
    val label = stringResource(R.string.survey_snr, NumberFormatter.format(snr, SNR_DECIMALS))
    val fill = marginColor(fadeMargin(snr, spreadingFactor))
    Box(modifier.background(fill, shape).padding(padding), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = style.copy(fontFeatureSettings = "tnum"),
            color = contentOn(fill),
            maxLines = 1,
        )
    }
}

/**
 * [text] with [rssi]'s number in the colour Meshtastic's node list gives it, so a weak packet stands out. The last
 * match is taken, since the number follows any count; 0 is no reading and stays plain.
 */
@Composable
private fun rssiColoured(text: String, rssi: Int?): AnnotatedString {
    val number = rssi?.takeIf { it != 0 }?.toString()
    val at = number?.let(text::lastIndexOf) ?: -1
    val color = rssi?.let { rssiQuality(it).color() }
    return buildAnnotatedString {
        append(text)
        if (number != null && color != null && at >= 0) addStyle(SpanStyle(color = color), at, at + number.length)
    }
}

/** The band core/ui's `Rssi` draws [rssi] (dBm) in: its thresholds, kept here so core/ui stays as upstream has it. */
private fun rssiQuality(rssi: Int): Quality = when {
    rssi > RSSI_GOOD_THRESHOLD -> Quality.GOOD
    rssi > RSSI_FAIR_THRESHOLD -> Quality.FAIR
    rssi > RSSI_BAD_THRESHOLD -> Quality.BAD
    else -> Quality.NONE
}

/** Black or white, whichever has more contrast on [fill]. */
private fun contentOn(fill: Color): Color {
    val shifted = fill.luminance() + LUMINANCE_FLARE
    // Black's contrast is shifted / flare, white's (1 + flare) / shifted: black wins once shifted² passes the product.
    return if (shifted * shifted > (1 + LUMINANCE_FLARE) * LUMINANCE_FLARE) Color.Black else Color.White
}

private const val SNR_DECIMALS = 1
private const val LUMINANCE_FLARE = 0.05f
private val SNR_CHIP_PADDING = 5.dp

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

/** Compact relay label for car mode: the byte and single resolved node name when known, else just the byte. */
@Composable
private fun carRelayText(relayByte: Int, relay: RelayStats?, names: Map<Long, String>): String {
    val byte = relayByteText(relayByte)
    val node = relay?.shownNode ?: return byte
    return stringResource(R.string.survey_last_packet_car_relay, byte, nodeName(node, names))
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
 * An ended session's relays, rebuilt from its saved packets ([SurveyViewModel.opened]), in a sheet under its date and
 * length. The guesses use the node database and SigSleuth's list as they are now; SigSleuth's answers are the ones
 * saved with its uploads, the newest per relay byte.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRelaysSheet(opened: OpenedSession, onDismiss: () -> Unit) {
    val sheetState =
        rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) { SessionRelays(opened) }
}

/** [SessionRelaysSheet]'s content: the relays list under the session's date and length. */
@Composable
internal fun SessionRelays(opened: OpenedSession) {
    val clock = rememberSurveyClock()
    RelaysList(
        opened.relays?.relays,
        opened.relays?.neighbours.orEmpty(),
        opened.session.spreadingFactor,
        opened.names,
        clock::value,
        // The rows' own inset brings their text in line with the title.
        contentPadding = PaddingValues(start = SHEET_INSET, end = SHEET_INSET, bottom = 24.dp),
        header = {
            Column(
                Modifier.padding(start = ROW_INSET, end = ROW_INSET, bottom = 8.dp).semantics(mergeDescendants = true) {
                    heading()
                },
            ) {
                Text(stringResource(R.string.survey_relays_title), style = MaterialTheme.typography.headlineSmall)
                Text(
                    sessionTitle(opened.session),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A sheet's edge to its rows, so with [ROW_INSET] their text sits 24 dp in, as sheet titles do. */
private val SHEET_INSET = 8.dp

/**
 * Relay bytes, most packets first, so rows keep their places as packets come: one line each, which a tap opens
 * ([RelayEntry]). The row of [lastRelayByte], the last packet's relay, is tinted. [header] comes first and scrolls with
 * the rows. A spinner while [relays] is null, as an ended session's are rebuilt.
 */
@Composable
@Suppress("LongParameterList")
private fun RelaysList(
    relays: Map<Int, RelayStats>?,
    neighbours: Map<Long, DirectSender>,
    spreadingFactor: Int,
    names: Map<Long, String>,
    clock: () -> SurveyNow,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    lastRelayByte: Int? = null,
    car: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    val sorted = remember(relays) { relays?.entries?.sortedByDescending { it.value.packets } }
    val snapshotOpen = LocalSurveySnapshot.current?.openRelays.orEmpty()
    var open by remember { mutableStateOf(snapshotOpen) }
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        header?.let { item(key = "header") { it() } }
        when {
            sorted == null ->
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

            sorted.isEmpty() ->
                item {
                    Text(
                        stringResource(R.string.survey_relays_none),
                        modifier = Modifier.padding(horizontal = ROW_INSET, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            else ->
                items(sorted, key = { it.key }) { (byte, relay) ->
                    val opened = byte in open
                    RelayEntry(
                        byte,
                        relay,
                        spreadingFactor,
                        neighbours,
                        names,
                        clock,
                        opened = opened,
                        latest = byte == lastRelayByte,
                        car = car,
                        onToggle = { open = if (opened) open - byte else open + byte },
                    )
                }
        }
    }
}

/** How far a relay row's text sits inside its tint. */
private val ROW_INSET = 16.dp
private const val LATEST_TINT_ALPHA = 0.16f

/**
 * One relay byte on a line: how sure, who, the last packet's SNR and when it came. A tap [opened] it on that packet's
 * count and RSSI, SigSleuth's answer and the candidates, the guessed ones standing out and the rest muted. Tinted while
 * it is the [latest] packet's relay. In [car] mode it leaves out the time and does not open.
 */
@Composable
@Suppress("LongParameterList")
private fun RelayEntry(
    relayByte: Int,
    relay: RelayStats,
    spreadingFactor: Int,
    neighbours: Map<Long, DirectSender>,
    names: Map<Long, String>,
    clock: () -> SurveyNow,
    opened: Boolean,
    latest: Boolean,
    car: Boolean,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // A faint wash of the primary colour: a grey one looked like another card.
    val tint by
        animateColorAsState(
            if (latest) colors.primary.copy(alpha = LATEST_TINT_ALPHA) else Color.Transparent,
            label = "latest",
        )
    val latestText = stringResource(R.string.survey_relay_latest)
    val toggleLabel = stringResource(if (opened) R.string.survey_relay_hide else R.string.survey_relay_show)
    val showDetails = opened && !car
    Column(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint)
            .then(if (car) Modifier else Modifier.clickable(onClickLabel = toggleLabel, onClick = onToggle))
            .semantics { if (latest) stateDescription = latestText }
            .padding(horizontal = if (car) 12.dp else ROW_INSET, vertical = if (car) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MARK_GAP)) {
            ConfidenceMark(relay)
            Text(
                relayByteMarked(relayText(relayByte, relay, names), relayByte),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                maxLines = if (showDetails) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            relay.last.rxSnr?.let { SnrChip(it, spreadingFactor) }
            if (!car) {
                Text(
                    agoText(relay.lastHeardMs, clock().wallMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (showDetails) RelayDetails(relay, neighbours, names, clock().wallMs)
    }
}

/**
 * An opened [RelayEntry]: its packet count and the last packet's RSSI, SigSleuth's answer, every candidate with how it
 * is known, measured from where that packet was heard (the guess was made there, not where the phone is now), and the
 * nodes its signal ruled out. Times are measured from [now].
 */
@Composable
private fun RelayDetails(relay: RelayStats, neighbours: Map<Long, DirectSender>, names: Map<Long, String>, now: Long) {
    val packet = relay.last
    val guessed = relay.guess.nodeIds.toSet()
    fun tone(nodeId: Long) = if (nodeId in guessed) LineTone.GUESS else LineTone.OTHER
    Column(Modifier.padding(start = MARK_SIZE + MARK_GAP), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PacketLine(relay)
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

/**
 * How sure the name beside it is: SigSleuth's badge, in its colour, when it named the relay; a green check for a direct
 * packet or this phone's likely guess; an amber question mark when the guess is only possible or ambiguous; and a grey
 * one when no candidate is left. The question mark is the module's own: MeshtasticIcons.HelpOutline draws an "i".
 */
@Composable
private fun ConfidenceMark(relay: RelayStats, size: Dp = MARK_SIZE) {
    val modifier = Modifier.size(size)
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

/** The packet count, then the last packet's RSSI in its colour when the radio gave it; its SNR is on the row above. */
@Composable
private fun PacketLine(relay: RelayStats) {
    val count = pluralStringResource(R.plurals.survey_relay_packets, relay.packets, relay.packets)
    val rssi = relay.last.rxRssi?.takeIf { it != 0 }
    Text(
        if (rssi == null) {
            AnnotatedString(count)
        } else {
            rssiColoured(stringResource(R.string.survey_relay_rssi, count, rssi), rssi)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        evidence == null && sameAsHeader ->
            stringResource(R.string.survey_relay_server_same, answerHowText(answer, names), time)

        evidence == null ->
            stringResource(R.string.survey_relay_server, nodeName(node, names), answerHowText(answer, names), time)

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

/** How SigSleuth knows the relay: a near link's node and distance when SigSleuth sent them, else [howText]. */
@Composable
@ReadOnlyComposable
private fun answerHowText(answer: ServerAnswer, names: Map<Long, String>): String {
    val near = answer.near
    return if (answer.how == "near_link" && near != null) {
        stringResource(
            R.string.survey_server_near_link_node,
            names[near.nodeId] ?: near.name ?: nodeName(near.nodeId, names),
            distanceText(near.km * METERS_PER_KM),
        )
    } else {
        howText(answer.how)
    }
}

/** How SigSleuth knows the relay, from [ServerAnswer.how]. */
@Composable
@ReadOnlyComposable
private fun howText(how: String): String = when (how) {
    "direct" -> stringResource(R.string.survey_server_direct)
    "survey_link" -> stringResource(R.string.survey_server_survey_link)
    "gateway_link" -> stringResource(R.string.survey_server_gateway_link)
    "near_link" -> stringResource(R.string.survey_server_near_link)
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

/** The wall clock and the clock GPS fixes are timed on, read together. */
internal data class SurveyNow(val wallMs: Long, val elapsedMs: Long) {
    companion object {
        fun read() = SurveyNow(nowMillis, SystemClock.elapsedRealtime())
    }
}

/** Both clocks, read together every second, or stopped at the [LocalSurveySnapshot]'s time. */
@Composable
private fun rememberSurveyClock(): State<SurveyNow> {
    val stopped = LocalSurveySnapshot.current?.now
    val clock = remember { mutableStateOf(stopped ?: SurveyNow.read()) }
    if (stopped == null) {
        LaunchedEffect(clock) {
            while (true) {
                delay(MILLIS_PER_SECOND)
                clock.value = SurveyNow.read()
            }
        }
    }
    return clock
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
 * How long before [now] [timeMs] was, without "ago" ("12 s", "3 min", "2 h"), for compact car-mode headers; beyond a
 * day it falls back to the system's relative time.
 */
@Composable
@ReadOnlyComposable
private fun shortAgeText(timeMs: Long, now: Long): String {
    val seconds = ((now - timeMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> stringResource(R.string.survey_seconds, seconds.toInt())
        seconds < SECONDS_PER_HOUR -> stringResource(R.string.survey_minutes, (seconds / SECONDS_PER_MINUTE).toInt())
        seconds < SECONDS_PER_DAY -> stringResource(R.string.survey_hours, (seconds / SECONDS_PER_HOUR).toInt())
        else -> DateFormatter.formatRelativeTime(timeMs)
    }
}

/**
 * How long before [now] [timeMs] was, coarsely: "12 s ago", "3 min ago", "2 h ago", then the system's words ("3 days
 * ago"). The system's alone counts whole minutes, which says "0 min. ago" for the packet just heard.
 */
@Composable
@ReadOnlyComposable
private fun agoText(timeMs: Long, now: Long): String {
    val seconds = ((now - timeMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
    if (seconds >= SECONDS_PER_DAY) return DateFormatter.formatRelativeTime(timeMs)
    return stringResource(R.string.survey_ago, shortAgeText(timeMs, now))
}

/**
 * Where uploads go, folded to one row once set: the server's host, which opens the form on a tap, and a small automatic
 * switch. Before setup it says uploads need a SigSleuth server. A line under the host counts the [pastWaiting] ended
 * sessions SigSleuth may have but lacks rows of, and a tap on it sends them. The cloud turns green when nothing waits
 * and [anyUploaded], so private sessions alone don't read as uploaded.
 */
@Composable
@Suppress("LongParameterList")
private fun UploadCard(
    settings: SurveyUploadSettings,
    pastWaiting: Int,
    anyUploaded: Boolean,
    uploading: Boolean,
    onSave: (SurveyUploadSettings) -> Unit,
    onAutomatic: (Boolean) -> Unit,
    onUploadAll: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        when {
            editing ->
                Box(Modifier.padding(16.dp)) {
                    ServerForm(
                        settings,
                        onSave = { edited ->
                            if (edited != settings) onSave(edited)
                            editing = false
                        },
                        onCancel = { editing = false },
                    )
                }

            settings.url.isBlank() ->
                Row(
                    Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(UPLOAD_ICON_GAP),
                ) {
                    Icon(
                        MeshtasticIcons.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(UPLOAD_ICON_SIZE),
                        tint = colors.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.survey_upload_set_up_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editing = true }) { Text(stringResource(R.string.survey_upload_set_up)) }
                }

            else ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val done = pastWaiting == 0 && anyUploaded
                        Row(
                            Modifier.weight(1f)
                                .clickable(onClickLabel = stringResource(R.string.survey_upload_edit)) {
                                    editing = true
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(UPLOAD_ICON_GAP),
                        ) {
                            Icon(
                                if (done) MeshtasticIcons.MqttDelivered else MeshtasticIcons.CloudUpload,
                                contentDescription = if (done) stringResource(R.string.survey_upload_done) else null,
                                modifier = Modifier.size(UPLOAD_ICON_SIZE),
                                tint = if (done) colors.StatusGreen else colors.onSurfaceVariant,
                            )
                            Text(
                                uploadUrlTyped(settings.url).trimEnd('/'),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Icon(
                                MeshtasticIcons.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(INFO_ICON_SIZE),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                        // The label and switch toggle together; the label reads as the full name.
                        val automatic = stringResource(R.string.survey_upload_automatic)
                        Row(
                            Modifier.toggleable(
                                value = settings.automatic,
                                role = Role.Switch,
                                onValueChange = onAutomatic,
                            )
                                .padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.survey_upload_auto),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.semantics { contentDescription = automatic },
                            )
                            Switch(
                                checked = settings.automatic,
                                onCheckedChange = null,
                                modifier = Modifier.scale(SMALL_SWITCH_SCALE),
                            )
                        }
                    }
                    // Sends only sessions already allowed; a private one is sent from its own card, so nothing goes
                    // that the user did not pick.
                    if (pastWaiting > 0) {
                        PastWaitingLine(pastWaiting, uploading, onUploadAll)
                    }
                }
        }
    }
}

/** The upload card's tertiary line under the host, in the waiting sessions' outline colour; a tap uploads them. */
@Composable
private fun PastWaitingLine(pastWaiting: Int, uploading: Boolean, onUploadAll: () -> Unit) {
    val color = MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !uploading, onClickLabel = stringResource(R.string.survey_upload_all)) {
                onUploadAll()
            }
            .padding(start = UPLOAD_ICON_SIZE + UPLOAD_ICON_GAP, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(INFO_ICON_GAP),
    ) {
        if (uploading) {
            CircularProgressIndicator(Modifier.size(INFO_ICON_SIZE), color = color, strokeWidth = 2.dp)
            Text(stringResource(R.string.survey_uploading), style = MaterialTheme.typography.bodyMedium, color = color)
        } else {
            Text(
                pluralStringResource(R.plurals.survey_upload_past_waiting_tap, pastWaiting, pastWaiting),
                style = MaterialTheme.typography.bodyMedium,
                color = color,
            )
        }
    }
}

private val UPLOAD_ICON_SIZE = 20.dp
private val UPLOAD_ICON_GAP = 12.dp

/** The upload card's automatic switch, smaller than a settings row's so it sits beside the host. */
private const val SMALL_SWITCH_SCALE = 0.75f

/**
 * The upload card's status, switch and Upload now while [recording] records, in a sheet the header's cloud opens. The
 * server is edited while nothing records.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
private fun UploadSheet(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    pastWaiting: Int,
    uploading: Boolean,
    onAutomatic: (Boolean) -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        UploadSheetContent(settings, recording, pastWaiting, uploading, onAutomatic, onUpload)
    }
}

/** [UploadSheet]'s content. */
@Composable
@Suppress("LongParameterList")
internal fun UploadSheetContent(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    pastWaiting: Int,
    uploading: Boolean,
    onAutomatic: (Boolean) -> Unit = {},
    onUpload: () -> Unit = {},
) {
    Column(
        Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.survey_upload),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        UploadSummary(settings, recording, pastWaiting, onAutomatic) {
            UploadButton(R.string.survey_upload_now, (recording?.pendingCount ?: 0) > 0, uploading, onUpload)
        }
    }
}

/**
 * The server, then what [recording] has waiting or why its last upload failed, the [pastWaiting] ended sessions, the
 * automatic switch and [button].
 */
@Composable
@Suppress("LongParameterList")
private fun UploadSummary(
    settings: SurveyUploadSettings,
    recording: SurveySessionSummary?,
    pastWaiting: Int,
    onAutomatic: (Boolean) -> Unit,
    button: @Composable () -> Unit,
) {
    val pending = recording?.pendingCount ?: 0
    val error = recording?.session?.lastUploadError?.takeIf { pending > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            uploadUrlTyped(settings.url).trimEnd('/'),
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

            pastWaiting == 0 && recording != null ->
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
        button()
    }
}

/** Shows Uploading… with a spinner during any run, which may be another session's. */
@Composable
private fun UploadButton(@StringRes label: Int, enabled: Boolean, uploading: Boolean, onClick: () -> Unit) {
    // Outlined in the primary colour, so it reads as a button on the card without competing with Stop.
    val active = enabled && !uploading
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        enabled = active,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, if (active) colors.primary else colors.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary),
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

/**
 * A refused password, which [SurveyUploader] words "HTTP 401" or "HTTP 403", is the one failure the user fixes here; no
 * connection ([SurveyUploader.NO_CONNECTION]) is said in words rather than as the lookup error.
 */
@Composable
@ReadOnlyComposable
private fun uploadErrorText(error: String): String = when (error) {
    "HTTP 401",
    "HTTP 403",
    -> stringResource(R.string.survey_upload_failed_password, error)

    SurveyUploader.NO_CONNECTION -> stringResource(R.string.survey_upload_failed_offline)

    else -> stringResource(R.string.survey_upload_failed, error)
}

@Composable
private fun ServerForm(settings: SurveyUploadSettings, onSave: (SurveyUploadSettings) -> Unit, onCancel: () -> Unit) {
    var url by rememberSaveable { mutableStateOf(uploadUrlTyped(settings.url)) }
    var user by rememberSaveable { mutableStateOf(settings.user) }
    var password by rememberSaveable { mutableStateOf(settings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val edited = SurveyUploadSettings(uploadUrlFrom(url), user.trim(), password, settings.automatic)
    val urlInvalid = edited.url.isNotEmpty() && !isUploadUrl(edited.url)
    val save = { if (!urlInvalid) onSave(edited) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.survey_upload_url)) },
            placeholder = { Text("sigsleuth.example.org") },
            isError = urlInvalid,
            supportingText =
            if (urlInvalid) {
                { Text(stringResource(R.string.survey_upload_url_invalid)) }
            } else {
                null
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
 * list of sessions on one radio does not repeat it. A tap, or its Relays chip as on the recording card, opens its
 * relays ([onOpen], null without packets). Export and Delete wait in a menu; Upload shows while rows are missing.
 */
@Composable
@Suppress("LongParameterList")
private fun SessionCard(
    summary: SurveySessionSummary,
    waiting: Boolean,
    uploadsOn: Boolean,
    uploading: Boolean,
    showRadio: Boolean,
    onOpen: (() -> Unit)?,
    onUpload: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val session = summary.session
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val canUpload = uploadsOn && summary.pendingCount > 0
    // A waiting session is outlined in the colour of the Upload card's waiting line, so it shows while scrolling.
    val border = if (waiting) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null
    val content: @Composable ColumnScope.() -> Unit = {
        // The bottom row's buttons bring their own touch padding, so the card's is smaller under them.
        Column(
            Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sessionTitle(session),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                SessionBadge(summary, waiting)
            }
            val news = session.news ?: NO_NEWS
            if (news.cells > 0 || summary.relayCount > 0) {
                NewsStats(news, relays = summary.relayCount, onRelays = onOpen)
            }
            UploadProgress(summary, uploadsOn)
            // The details, small and muted under what the session found.
            Column {
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                if (showRadio) Text(radioText(session), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(sessionFacts(summary), style = MaterialTheme.typography.bodySmall, color = muted)
                serverText(session)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
            session.lastUploadError?.let {
                Text(
                    uploadErrorText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // The menu sits down here, not beside the date, so a long badge never pushes the date onto two lines.
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
                SessionMenu(onExport, onDelete = { confirmDelete = true })
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
    if (onOpen == null) {
        Card(Modifier.fillMaxWidth(), border = border, content = content)
    } else {
        Card(onClick = onOpen, Modifier.fillMaxWidth(), border = border, content = content)
    }
}

/**
 * Export and Delete: every ended session has them, so they wait behind ⋮. Sessions show only while nothing records, as
 * a delete recounts later sessions ([SurveyViewModel.delete]).
 */
@Composable
private fun SessionMenu(onExport: () -> Unit, onDelete: () -> Unit) {
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

private val MENU_BUTTON_INSET = 12.dp

/**
 * Relays, cells and senders heard directly, each with what was new to this phone under it in green, and under the
 * cells, once SigSleuth has answered, the [mapCells] its map gained. With [onRelays], the relays figure opens their
 * list; it comes first, where a button is easy to find. In [car] mode only the new cells keep their note.
 */
@Composable
private fun NewsStats(
    news: SurveyNews,
    relays: Int,
    mapCells: Int? = news.newCells,
    packets: Int? = null,
    onRelays: (() -> Unit)? = null,
    car: Boolean = false,
) {
    @Composable
    @ReadOnlyComposable
    fun newCount(count: Int) = count.takeIf { it > 0 && !car }?.let { stringResource(R.string.survey_stat_new, it) }

    @Composable
    @ReadOnlyComposable
    fun plus(count: Int) = count.takeIf { it > 0 && car }?.let { stringResource(R.string.survey_stat_plus, it) }

    Row(Modifier.fillMaxWidth().bleed(STAT_INSET), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Stat(
            relays.toString(),
            Figure.RELAYS,
            Modifier.weight(1f),
            inlineNote = plus(news.localNewRelays),
            note = newCount(news.localNewRelays),
            onClick = onRelays,
            onClickLabel = stringResource(R.string.survey_relays_open),
        )
        Stat(
            news.cells.toString(),
            Figure.CELLS,
            Modifier.weight(1f),
            inlineNote = plus(news.localNewCells),
            note = newCount(news.localNewCells),
            mapNote = mapCells?.let { stringResource(R.string.survey_stat_added, it) },
        )
        Stat(
            news.direct.toString(),
            Figure.DIRECT,
            Modifier.weight(1f),
            inlineNote = plus(news.localNewDirect),
            note = newCount(news.localNewDirect),
        )
        packets?.let { Stat(it.toString(), Figure.PACKETS, Modifier.weight(1f)) }
    }
}

/**
 * Where a session stands, beside its date: waiting to upload (blue, as its border) or uploaded (green). Nothing for a
 * session that is private or was never sent.
 */
@Composable
private fun SessionBadge(summary: SurveySessionSummary, waiting: Boolean) {
    val (icon, text, color) =
        when {
            waiting ->
                Triple(MeshtasticIcons.CloudUpload, R.string.survey_session_waiting, MaterialTheme.colorScheme.tertiary)

            summary.measurementCount > 0 && summary.uploadedCount >= summary.measurementCount ->
                Triple(MeshtasticIcons.CheckCircle, R.string.survey_uploaded_all, MaterialTheme.colorScheme.StatusGreen)

            else -> return
        }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(INFO_ICON_SIZE), tint = color)
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/**
 * The cells, the senders heard directly and the cells added to the map of every session on this phone, in the cards'
 * colours, so the status colours keep their contrast; the biggest numbers on the screen set it apart.
 */
@Composable
private fun TotalsCard(totals: SurveyTotals) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.survey_totals), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TotalStat(totals.cells, Figure.CELLS, Modifier.weight(1f))
                TotalStat(totals.direct, Figure.DIRECT, Modifier.weight(1f))
                totals.mapCells?.let { TotalStat(it, Figure.MAP, Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A [TotalsCard] figure: [Stat]'s layout, larger. The cells added to the map are in SigSleuth's colour, as under a
 * session's cells.
 */
@Composable
private fun TotalStat(value: Int, figure: Figure, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}.padding(vertical = 4.dp)) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
            color = if (figure == Figure.MAP) figure.color() else Color.Unspecified,
            maxLines = 1,
        )
        FigureLabel(figure)
    }
}

/** When it started and, once ended, how long it ran: "16 Nov 2026 10:02 · 0:07:25". */
private fun sessionTitle(session: SurveySession): String {
    val start = DateFormatter.formatDateTime(session.startedAt)
    val duration = session.endedAt?.let { clockText(it - session.startedAt) } ?: return start
    return "$start · $duration"
}

/** Size and, when it ended on its own, how: "1234 measurements · Ended: app was closed". A Stop goes unsaid. */
@Composable
private fun sessionFacts(summary: SurveySessionSummary): String {
    val count = pluralStringResource(R.plurals.survey_packets, summary.measurementCount, summary.measurementCount)
    if (summary.session.status == SurveyStatus.STOPPED) return count
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
        // The default track is the card's own colour, which left only the bar and its end dot showing.
        LinearProgressIndicator(
            progress = { uploaded.toFloat() / count },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

/** How many packets SigSleuth kept and skipped; the reasons stay out, as a list of them crowded the card. */
@Composable
@ReadOnlyComposable
private fun serverText(session: SurveySession): String? {
    if (session.serverKept + session.serverSkipped == 0L) return null
    return if (session.serverSkipped == 0L) {
        stringResource(R.string.survey_server_kept_all, session.serverKept)
    } else {
        stringResource(R.string.survey_server_kept, session.serverKept, session.serverSkipped)
    }
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
