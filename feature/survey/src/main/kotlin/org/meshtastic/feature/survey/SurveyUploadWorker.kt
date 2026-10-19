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

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.android.annotation.KoinWorker
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Tries a failed automatic upload again later, so a session ended out of coverage does not wait for the next survey.
 */
interface SurveyUploadRetry {
    /** Queues a retry unless one is already waiting; a waiting one keeps its backoff. */
    fun schedule()
}

@Single
class WorkManagerSurveyUploadRetry(private val context: Application) : SurveyUploadRetry {
    override fun schedule() {
        val request =
            OneTimeWorkRequestBuilder<SurveyUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF.toJavaDuration())
                .build()
        // KEEP: a failure while a retry waits or runs must not restart its backoff or cancel its upload.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    private companion object {
        const val WORK_NAME = "signal_survey_upload"
        val BACKOFF = 1.minutes
    }
}

/** Runs once the phone has a network connection, and again with a growing delay while the upload keeps failing. */
@KoinWorker
class SurveyUploadWorker(appContext: Context, workerParams: WorkerParameters, private val uploader: SurveyUploader) :
    CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = if (uploader.uploadIfAutomatic()) Result.success() else Result.retry()
}
