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

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.meshtastic.core.common.di.ApplicationCoroutineScope

/**
 * The Signal Survey Quick Settings tile, lit while a session records. A tap stops the session, also on the lock screen;
 * otherwise it unlocks the phone and opens the survey screen, which starts one ([SurveyStartRequest]).
 */
class SurveyTileService : TileService() {
    private val recorder: SurveyRecorder by inject()
    private val startRequest: SurveyStartRequest by inject()
    private val appScope: ApplicationCoroutineScope by inject()
    private val mainScope = MainScope()
    private var listening: Job? = null

    override fun onStartListening() {
        listening?.cancel()
        listening = mainScope.launch {
            recorder.state.map { it.session != null }.distinctUntilChanged().collect(::show)
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onDestroy() {
        mainScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        if (recorder.state.value.session != null) {
            // The session's own scope, not this service's: the system may unbind the tile before Stop finishes.
            appScope.launch { recorder.stop() }
        } else {
            unlockAndRun {
                startRequest.request()
                openScreen()
            }
        }
    }

    private fun openScreen() {
        val intent = surveyScreenIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            collapseAndStart(intent)
        }
    }

    // The PendingIntent form exists only from Android 14, and the Intent one throws only from there, which openScreen
    // keeps it away from. Lint can't see the version check through its own rule's ID.
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun collapseAndStart(intent: Intent) = startActivityAndCollapse(intent)

    private fun show(recording: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (recording) getString(R.string.survey_recording) else null
        }
        tile.updateTile()
    }
}
