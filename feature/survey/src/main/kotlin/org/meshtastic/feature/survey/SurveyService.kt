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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import kotlin.time.Duration.Companion.seconds

/**
 * Keeps the app alive with location access while a survey records. MeshService is not enough: after a start at boot it
 * holds only the connectedDevice type, without location.
 *
 * The screen starts it; it starts the recorder and stops itself when the session ends, however it ends.
 */
class SurveyService : Service() {
    private val recorder: SurveyRecorder by inject()
    private val uploader: SurveyUploader by inject()
    private val scope: ApplicationCoroutineScope by inject()
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("ReturnCount")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (job?.isActive == true) {
                scope.launch { recorder.stop() }
            } else {
                // A notification left behind by an earlier service: nothing is recording, so just remove it.
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job != null) return START_NOT_STICKY
        @Suppress("TooGenericExceptionCaught") // Android 12+ throws several types when it refuses a foreground start.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(recorder.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (error: Exception) {
            Logger.w { "Signal survey could not start in the foreground: ${error::class.simpleName}" }
            stopSelf()
            return START_NOT_STICKY
        }
        job = scope.launch {
            if (uploader.startSession { recorder.start(upload = it) }) {
                val whileRecording = listOf(launch { updateNotification() }, launch { uploadWhileRecording() })
                recorder.state.first { it.session == null }
                // Join, not just cancel: a notify() still running would bring the notification back after removal.
                whileRecording.forEach { it.cancelAndJoin() }
                scope.launch { uploader.uploadIfAutomatic() }
            }
            withContext(Dispatchers.Main) {
                ServiceCompat.stopForeground(this@SurveyService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun uploadWhileRecording() {
        while (true) {
            delay(SurveyUploader.RECORDING_INTERVAL)
            uploader.uploadIfAutomatic()
        }
    }

    private suspend fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        recorder.state
            .map { it.copy(fix = null, lastMeasurement = null) }
            .distinctUntilChanged()
            .conflate()
            .collect {
                manager.notify(NOTIFICATION_ID, notification(it))
                // Android drops an app's notification updates beyond about five a second, which can leave a stale
                // count showing; one a second is plenty, and conflate keeps the newest.
                delay(NOTIFICATION_INTERVAL)
            }
    }

    private fun notification(state: SurveyState): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.survey_title), NotificationManager.IMPORTANCE_LOW),
        )
        val stop =
            PendingIntent.getService(
                this,
                0,
                Intent(this, SurveyService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            if (state.waitingForRadio) {
                getString(R.string.survey_waiting_for_radio)
            } else {
                resources.getQuantityString(
                    R.plurals.survey_measurements,
                    state.measurementCount,
                    state.measurementCount,
                )
            }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.survey_ic_notification)
            .setContentTitle(getString(R.string.survey_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.survey_stop), stop)
            .apply {
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    setContentIntent(PendingIntent.getActivity(this@SurveyService, 0, it, PendingIntent.FLAG_IMMUTABLE))
                }
            }
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        // The system stopped the service while recording: end the session rather than record without a foreground.
        if (recorder.state.value.session != null) scope.launch { recorder.stop(SurveyStatus.INTERRUPTED) }
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "org.meshtastic.feature.survey.STOP"
        private const val CHANNEL_ID = "signal_survey"
        private const val NOTIFICATION_ID = 7319
        private val NOTIFICATION_INTERVAL = 1.seconds

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SurveyService::class.java))
        }
    }
}
