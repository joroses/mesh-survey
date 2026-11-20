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

    override suspend fun sessionsToUpload() = summaries()
        .filter { it.session.upload && it.lastSeq > it.session.uploadedSeq }
        .sortedBy { it.session.startedAt }

    override suspend fun uploadAllowed(sessionId: String) = sessions.any { it.id == sessionId && it.upload }

    override suspend fun setUpload(sessionId: String, upload: Boolean) =
        updateSession(sessionId) { it.copy(upload = upload) }

    override suspend fun measurementsAfter(sessionId: String, afterSeq: Long, limit: Int) =
        measurements.filter { it.sessionId == sessionId && it.seq > afterSeq }.take(limit)

    override suspend fun directPlaces(
        receiverNodeId: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
        sinceMs: Long,
        beforeMs: Long,
    ): List<DirectPlace> {
        val network = SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz)
        val sessionIds =
            sessions
                .filter {
                    it.receiverNodeId == receiverNodeId &&
                        SurveyNetwork(it.spreadingFactor, it.bandwidthKhz, it.frequencyMhz).same(network)
                }
                .map { it.id }
                .toSet()
        return measurements
            .filter { it.sessionId in sessionIds && it.heardAtMs in sinceMs..<beforeMs && it.isDirect() }
            .groupBy { row ->
                row.senderNodeId to row.latitude?.let { lat -> row.longitude?.let { lon -> DirectCell.of(lat, lon) } }
            }
            .map { (place, rows) ->
                val (nodeId, cell) = place
                DirectPlace(
                    nodeId,
                    rows.maxOf { it.heardAtMs },
                    cell?.latitude,
                    cell?.longitude,
                    rows.mapNotNull { it.signal() }.maxOrNull(),
                )
            }
    }

    override suspend fun cellKeys(
        cellX: Int,
        cellY: Int,
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ): List<CellKey> {
        val network = SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz)
        val earlier =
            sessions
                .filter {
                    it.startedAt < startedAt &&
                        SurveyNetwork(it.spreadingFactor, it.bandwidthKhz, it.frequencyMhz).same(network)
                }
                .map { it.id }
                .toSet()
        return measurements
            .filter { it.sessionId in earlier && it.cellX == cellX && it.cellY == cellY }
            .map { it.cellKey() }
            .distinct()
    }

    override suspend fun endSession(sessionId: String, status: SurveyStatus, endedAt: Long) =
        updateSession(sessionId) { it.copy(status = status, endedAt = endedAt) }

    override suspend fun interruptRecording() = sessions.replaceAll { session ->
        if (session.status != SurveyStatus.RECORDING) return@replaceAll session
        val last = measurements.filter { it.sessionId == session.id }.maxOfOrNull { it.heardAtMs }
        session.copy(status = SurveyStatus.INTERRUPTED, endedAt = last ?: session.startedAt)
    }

    override suspend fun recordingSessionIds() = sessions.filter { it.status == SurveyStatus.RECORDING }.map { it.id }

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

    override suspend fun resetSessionUploads() = sessions.replaceAll {
        it.copy(
            uploadedSeq = 0,
            lastUploadAt = null,
            lastUploadError = null,
            serverKept = 0,
            serverSkipped = 0,
            serverSkipReasons = null,
            news = it.news?.copy(newCells = null),
        )
    }

    override suspend fun clearServerNew() = measurements.replaceAll { it.copy(serverNew = null) }

    override suspend fun setServerNew(sessionId: String, seq: Long, flags: Int) = measurements.replaceAll {
        if (it.sessionId == sessionId && it.seq == seq) it.copy(serverNew = flags) else it
    }

    /** How many times a session's news was counted and saved. */
    var recounts = 0

    override suspend fun recountNews(sessionId: String): SurveyNews = super.recountNews(sessionId).also { recounts++ }

    override suspend fun countNews(sessionId: String): SurveyNews {
        val rows = measurements.filter { it.sessionId == sessionId && it.cellX != null }
        val answered = rows.any { it.serverNew != null }
        fun cellOf(row: SurveyMeasurement) = row.cellX to row.cellY
        fun cells(rows: List<SurveyMeasurement>) = rows.map(::cellOf).distinct().size
        fun local(bit: Int) = cells(rows.filter { ((it.localNew ?: 0) and bit) != 0 })
        return SurveyNews(
            cells = cells(rows),
            relays = rows.mapNotNull { it.relayByte }.distinct().size,
            direct = rows.filter { it.isDirect() }.map { it.senderNodeId }.distinct().size,
            newCells =
            if (answered) cells(rows.filter { ((it.serverNew ?: 0) and SurveyNews.NEW_CELL) != 0 }) else null,
            localNewCells = local(SurveyNews.NEW_CELL),
            localNewRelayCells = local(SurveyNews.NEW_NODE),
            localNewDirectCells = local(SurveyNews.NEW_DIRECT),
        )
    }

    override suspend fun countDirectSenders(): Int =
        measurements.filter { it.cellX != null && it.isDirect() }.map { it.senderNodeId }.distinct().size

    override suspend fun saveNews(
        sessionId: String,
        cells: Int,
        relays: Int,
        direct: Int,
        newCells: Int?,
        localNewCells: Int,
        localNewRelayCells: Int,
        localNewDirectCells: Int,
    ) = updateSession(sessionId) {
        it.copy(
            news =
            SurveyNews(cells, relays, direct, newCells, localNewCells, localNewRelayCells, localNewDirectCells),
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

    override suspend fun session(sessionId: String) = sessions.firstOrNull { it.id == sessionId }

    override suspend fun sessionCells(sessionId: String) =
        measurements.filter { it.sessionId == sessionId }.mapNotNull { it.cell() }.distinct()

    override suspend fun cellRows(cellX: Int, cellY: Int): List<CellRow> {
        val byId = sessions.associateBy { it.id }
        return measurements
            .filter { it.cellX == cellX && it.cellY == cellY }
            .mapNotNull { row ->
                byId[row.sessionId]?.let {
                    val network = SurveyNetwork(it.spreadingFactor, it.bandwidthKhz, it.frequencyMhz)
                    CellRow(
                        row.seq,
                        it.id,
                        it.startedAt,
                        network,
                        SurveyCell(cellX, cellY),
                        row.cellKey(),
                        row.localNew,
                    )
                }
            }
            .sortedWith(compareBy({ it.startedAt }, { it.sessionId }, { it.seq }))
    }

    override suspend fun setLocalNew(seq: Long, bits: Int) = measurements.replaceAll {
        if (it.seq == seq) it.copy(localNew = bits) else it
    }

    val deleted = MutableStateFlow<SurveyDeleted?>(null)

    override fun deleted() = deleted

    override suspend fun createDeleted() {
        if (deleted.value == null) deleted.value = SurveyDeleted()
    }

    override suspend fun addDeleted(cells: Int) {
        deleted.value = deleted.value?.let { it.copy(newCells = it.newCells + cells) }
    }

    override suspend fun clearDeleted() {
        deleted.value = null
    }
}
