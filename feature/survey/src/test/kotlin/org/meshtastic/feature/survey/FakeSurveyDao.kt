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

import kotlinx.coroutines.flow.MutableStateFlow

/** An in-memory [SurveyDao]; seqs count up across sessions as Room's do. */
internal class FakeSurveyDao : SurveyDao {
    val sessions = mutableListOf<SurveySession>()
    val measurements = mutableListOf<SurveyMeasurement>()

    private fun updateSession(id: String, change: (SurveySession) -> SurveySession) {
        sessions.replaceAll { if (it.id == id) change(it) else it }
    }

    override suspend fun summaries() = sessions.map { session ->
        val rows = measurements.filter { it.sessionId == session.id }
        SurveySessionSummary(
            session,
            measurementCount = rows.size,
            uploadedCount = rows.count { it.seq <= session.uploadedSeq },
            lastSeq = rows.maxOfOrNull { it.seq } ?: 0,
        )
    }

    override suspend fun insertSession(session: SurveySession) {
        sessions += session
    }

    /** Makes [insertMeasurement] fail as a full disk would. */
    var failInserts = false

    override suspend fun insertMeasurement(measurement: SurveyMeasurement): Long {
        check(!failInserts) { "disk full" }
        measurements += measurement.copy(seq = measurements.size + 1L)
        return measurements.size.toLong()
    }

    override fun sessionRows() = MutableStateFlow(sessions.toList())

    override suspend fun sessionsToUpload() = summaries().filter { it.lastSeq > it.session.uploadedSeq }

    override suspend fun measurementsAfter(sessionId: String, afterSeq: Long, limit: Int) =
        measurements.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)

    override suspend fun directPlaces(
        receiverNodeId: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
        sinceMs: Long,
    ): List<DirectPlace> {
        val sessionIds =
            sessions
                .filter {
                    it.receiverNodeId == receiverNodeId &&
                        it.spreadingFactor == spreadingFactor &&
                        it.bandwidthKhz == bandwidthKhz &&
                        it.frequencyMhz == frequencyMhz
                }
                .map { it.id }
                .toSet()
        return measurements
            .filter { it.sessionId in sessionIds && it.heardAtMs >= sinceMs && it.isDirect() }
            .groupBy { row ->
                row.senderNodeId to row.latitude?.let { lat -> row.longitude?.let { lon -> DirectCell.of(lat, lon) } }
            }
            .map { (place, rows) ->
                val (nodeId, cell) = place
                DirectPlace(nodeId, rows.maxOf { it.heardAtMs }, cell?.latitude, cell?.longitude)
            }
    }

    override suspend fun endSession(sessionId: String, status: SurveyStatus, endedAt: Long) =
        updateSession(sessionId) { it.copy(status = status, endedAt = endedAt) }

    override suspend fun interruptRecording() = Unit

    override suspend fun uploaded(
        sessionId: String,
        uploadedSeq: Long,
        at: Long,
        kept: Long,
        skipped: Long,
        skipReasons: String?,
    ) = updateSession(sessionId) {
        it.copy(
            uploadedSeq = maxOf(it.uploadedSeq, uploadedSeq),
            lastUploadAt = at,
            lastUploadError = null,
            serverKept = kept,
            serverSkipped = skipped,
            serverSkipReasons = skipReasons,
        )
    }

    override suspend fun resetUploads() = sessions.replaceAll {
        it.copy(
            uploadedSeq = 0,
            lastUploadAt = null,
            lastUploadError = null,
            serverKept = 0,
            serverSkipped = 0,
            serverSkipReasons = null,
        )
    }

    override suspend fun uploadFailed(sessionId: String, error: String) =
        updateSession(sessionId) { it.copy(lastUploadError = error) }

    override suspend fun deleteMeasurements(sessionId: String) {
        measurements.removeAll { it.sessionId == sessionId }
    }

    override suspend fun deleteSessionRow(sessionId: String) {
        sessions.removeAll { it.id == sessionId }
    }
}
