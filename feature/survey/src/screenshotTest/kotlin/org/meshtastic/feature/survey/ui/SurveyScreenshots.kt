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
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import org.meshtastic.core.common.ContextServices
import org.meshtastic.core.ui.theme.AppTheme
import org.meshtastic.feature.survey.R

/*
 * The survey screen from made-up data ([SurveyScenarios]), drawn without a phone. Make the pictures with
 * `:feature:survey:updateDebugScreenshotTest` (they land in src/screenshotTestDebug/reference/); check a change against
 * them with `:feature:survey:validateDebugScreenshotTest`. Both need `-Dorg.gradle.isolated-projects=false`, as CI's
 * screenshot job passes: the screenshot plugin reads every build service, which Isolated Projects forbids. The pictures
 * stay out of git; make them before a change and compare after it.
 */

/** A phone in light and dark. */
@Preview(name = "light", widthDp = PHONE_WIDTH, heightDp = PHONE_HEIGHT, showBackground = true)
@Preview(
    name = "dark",
    widthDp = PHONE_WIDTH,
    heightDp = PHONE_HEIGHT,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
annotation class SurveyPreviews

/** A small phone with the largest text, and a phone on its side. */
@Preview(name = "small_big_text", widthDp = 360, heightDp = 640, fontScale = 1.5f, showBackground = true)
@Preview(name = "landscape", widthDp = PHONE_HEIGHT, heightDp = PHONE_WIDTH, showBackground = true)
annotation class SurveyHardPreviews

/** A tall window, to see a whole list at once. */
@Preview(name = "tall", widthDp = PHONE_WIDTH, heightDp = 2_000, showBackground = true)
annotation class SurveyTallPreview

private const val PHONE_WIDTH = 393
private const val PHONE_HEIGHT = 852

/** The app's theme with the clocks stopped at [SNAPSHOT_NOW] and the relay rows of [openRelays] opened. */
@Composable
private fun SurveyFrame(openRelays: Set<Int> = emptySet(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // DateFormatter reads the app, which nothing starts in a screenshot.
    ContextServices.app =
        object : Application() {
            init {
                attachBaseContext(context)
            }
        }
    AppTheme(dynamicColor = false) {
        CompositionLocalProvider(LocalSurveySnapshot provides SurveySnapshot(SNAPSHOT_NOW, openRelays)) { content() }
    }
}

@Composable
private fun Screen(ui: SurveyUi, openRelays: Set<Int> = emptySet()) =
    SurveyFrame(openRelays) { SurveyContent(ui, SurveyActions()) }

/** A sheet's content on a sheet's colour; a real sheet is a window of its own, which a screenshot does not catch. */
@Composable
private fun Sheet(openRelays: Set<Int> = emptySet(), content: @Composable () -> Unit) = SurveyFrame(openRelays) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
}

// ---- Before a session ----

@PreviewTest @SurveyPreviews @Composable
fun IdleFirstRun() = Screen(SurveyScenarios.firstRun)

@PreviewTest @SurveyPreviews @Composable
fun IdleReady() = Screen(SurveyScenarios.ready)

@PreviewTest @SurveyPreviews @SurveyHardPreviews @Composable
fun IdleHistory() = Screen(SurveyScenarios.history)

@PreviewTest @SurveyTallPreview @Composable
fun IdleHistoryAll() = Screen(SurveyScenarios.history)

@PreviewTest @SurveyPreviews @Composable
fun IdleTrouble() = Screen(SurveyScenarios.trouble)

@PreviewTest @SurveyPreviews @Composable
fun IdleUploading() = Screen(SurveyScenarios.uploading)

// ---- Recording ----

@PreviewTest @SurveyPreviews @Composable
fun RecordingJustStarted() = Screen(SurveyScenarios.justStarted)

@PreviewTest @SurveyPreviews @Composable
fun RecordingQuiet() = Screen(SurveyScenarios.quiet)

@PreviewTest @SurveyPreviews @SurveyHardPreviews @Composable
fun RecordingBusy() = Screen(SurveyScenarios.busy)

@PreviewTest
@SurveyPreviews
@Composable
fun RecordingBusyOpenRows() = Screen(SurveyScenarios.busy, SurveyScenarios.busyOpenRows)

@PreviewTest
@SurveyPreviews
@SurveyHardPreviews
@Composable
fun RecordingLotsOfNews() = Screen(SurveyScenarios.lotsOfNews)

@PreviewTest @SurveyPreviews @Composable
fun RecordingWaitingForRadio() = Screen(SurveyScenarios.waitingForRadio)

@PreviewTest @SurveyPreviews @Composable
fun RecordingNoGps() = Screen(SurveyScenarios.noGps)

@PreviewTest @SurveyPreviews @Composable
fun RecordingUploadFailed() = Screen(SurveyScenarios.uploadFailed)

@PreviewTest @SurveyPreviews @SurveyHardPreviews @Composable
fun CarBusy() = Screen(SurveyScenarios.carBusy)

@PreviewTest @SurveyPreviews @Composable
fun RecordingPinged() = Screen(SurveyScenarios.pinged)

@PreviewTest @SurveyPreviews @Composable
fun CarPinged() = Screen(SurveyScenarios.carPinged)

@PreviewTest @SurveyPreviews @Composable
fun RecordingPingedLongAgo() = Screen(SurveyScenarios.pingedLongAgo)

@PreviewTest @SurveyPreviews @Composable
fun RecordingPingRefused() = Screen(SurveyScenarios.pingRefused)

@PreviewTest @SurveyPreviews @Composable
fun CarPingRefused() = Screen(SurveyScenarios.pingRefused.copy(carMode = true))

@PreviewTest @SurveyPreviews @Composable
fun RecordingPingedByMany() = Screen(SurveyScenarios.pingedByMany)

@PreviewTest @SurveyPreviews @Composable
fun CarPingedByMany() = Screen(SurveyScenarios.carPingedByMany)

@PreviewTest @SurveyPreviews @Composable
fun CarWaiting() = Screen(SurveyScenarios.carWaiting)

// ---- Sheets ----

@PreviewTest
@SurveyPreviews
@Composable
fun SheetEndedRelays() = Sheet(SurveyScenarios.busyOpenRows) { SessionRelays(SurveyScenarios.endedRelays) }

@PreviewTest
@SurveyPreviews
@Composable
fun SheetEndedRelaysLoading() = Sheet { SessionRelays(SurveyScenarios.endedRelaysLoading) }

@PreviewTest
@SurveyPreviews
@Composable
fun SheetUpload() = Sheet {
    UploadSheetContent(
        SurveyScenarios.server.copy(automatic = false),
        SurveyScenarios.uploadSheetRecording,
        pastWaiting = 2,
        uploading = false,
    )
}

// ---- Tooltips ----

/**
 * The top bar's ⓘ opened, drawn like a rich tooltip (its colour, shape, width and title); a real one is a popup, which
 * a screenshot does not catch.
 */
@PreviewTest
@SurveyPreviews
@SurveyHardPreviews
@Composable
fun TooltipLegend() = SurveyFrame {
    Surface(
        Modifier.padding(16.dp).wrapContentSize(Alignment.TopStart).widthIn(max = 320.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.survey_legend),
                Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Legend(Modifier.verticalScroll(rememberScrollState()))
        }
    }
}
