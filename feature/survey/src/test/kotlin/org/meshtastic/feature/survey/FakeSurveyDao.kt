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
    val relayLists = mutableSetOf<SessionRelay>()
    val directLists = mutableSetOf<SessionDirect>()

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
            relayCount = rows.mapNotNull { it.relayByte }.distinct().size,
        )
    }

    override suspend fun insertSession(session: SurveySession) {
        sessions += session
    }

    /** Makes [insertMeasurement] fail as a full disk would. */
    var failInserts = false

    override suspend fun insertRow(measurement: SurveyMeasurement): Long {
        check(!failInserts) { "disk full" }
        measurements += measurement.copy(seq = measurements.size + 1L)
        return measurements.size.toLong()
    }

    override suspend fun addRelay(relay: SessionRelay) {
        relayLists += relay
    }

    override suspend fun addDirect(direct: SessionDirect) {
        directLists += direct
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
                )
            }
    }

    override suspend fun hasCell(
        cellX: Int,
        cellY: Int,
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ): Boolean {
        val network = SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz)
        val earlier =
            sessions
                .filter {
                    it.startedAt < startedAt &&
                        SurveyNetwork(it.spreadingFactor, it.bandwidthKhz, it.frequencyMhz).same(network)
                }
                .map { it.id }
                .toSet()
        return measurements.any { it.sessionId in earlier && it.cellX == cellX && it.cellY == cellY }
    }

    /** The sessions that started before [startedAt] on this network, as [SAME_NETWORK]. */
    private fun earlierIds(startedAt: Long, network: SurveyNetwork) = sessions
        .filter {
            it.startedAt < startedAt &&
                SurveyNetwork(it.spreadingFactor, it.bandwidthKhz, it.frequencyMhz).same(network)
        }
        .map { it.id }
        .toSet()

    override suspend fun earlierRelays(
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ): List<Int> {
        val earlier = earlierIds(startedAt, SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz))
        return relayLists.filter { it.sessionId in earlier }.map { it.relayByte }.distinct()
    }

    override suspend fun earlierDirect(
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ): List<Long> {
        val earlier = earlierIds(startedAt, SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz))
        return directLists.filter { it.sessionId in earlier }.map { it.nodeId }.distinct()
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
        val all = measurements.filter { it.sessionId == sessionId }
        val withCell = all.filter { it.cellX != null }
        val answered = withCell.any { it.serverNew != null }
        fun cellOf(row: SurveyMeasurement) = row.cellX to row.cellY
        fun cells(rows: List<SurveyMeasurement>) = rows.map(::cellOf).distinct().size
        return SurveyNews(
            cells = cells(withCell),
            relays = all.mapNotNull { it.relayByte }.distinct().size,
            direct = all.filter { it.isDirect() }.map { it.senderNodeId }.distinct().size,
            newCells =
            if (answered) cells(withCell.filter { ((it.serverNew ?: 0) and SurveyNews.NEW_CELL) != 0 }) else null,
            localNewCells = cells(withCell.filter { ((it.localNew ?: 0) and SurveyNews.NEW_CELL) != 0 }),
            localNewRelays = newKeys(sessionId).first,
            localNewDirect = newKeys(sessionId).second,
        )
    }

    /** [sessionId]'s relay bytes and direct senders no earlier session on its network has, as `NEW_RELAYS` counts. */
    private suspend fun newKeys(sessionId: String): Pair<Int, Int> {
        val session = sessions.firstOrNull { it.id == sessionId } ?: return 0 to 0
        val earlier = earlierKeys(session)
        val relays = relayLists.filter { it.sessionId == sessionId && it.relayByte !in earlier.relays }
        val direct = directLists.filter { it.sessionId == sessionId && it.nodeId !in earlier.direct }
        return relays.size to direct.size
    }

    override suspend fun countDirectSenders(): Int = directLists.map { it.nodeId }.distinct().size

    /** How many times [recountKeysAfter] ran. */
    var keyRecounts = 0

    override suspend fun recountKeysAfter(
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ) {
        keyRecounts++
        val network = SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz)
        for (later in sessions.filter { it.startedAt > startedAt && it.news != null }) {
            if (!SurveyNetwork(later.spreadingFactor, later.bandwidthKhz, later.frequencyMhz).same(network)) continue
            val (relays, direct) = newKeys(later.id)
            updateSession(later.id) { it.copy(news = it.news?.copy(localNewRelays = relays, localNewDirect = direct)) }
        }
    }

    override suspend fun saveNews(
        sessionId: String,
        cells: Int,
        relays: Int,
        direct: Int,
        newCells: Int?,
        localNewCells: Int,
        localNewRelays: Int,
        localNewDirect: Int,
    ) = updateSession(sessionId) {
        it.copy(news = SurveyNews(cells, relays, direct, newCells, localNewCells, localNewRelays, localNewDirect))
    }

    override suspend fun uploadFailed(sessionId: String, error: String) =
        updateSession(sessionId) { it.copy(lastUploadError = error) }

    override suspend fun deleteMeasurements(sessionId: String) {
        measurements.removeAll { it.sessionId == sessionId }
    }

    override suspend fun deleteRelays(sessionId: String) {
        relayLists.removeAll { it.sessionId == sessionId }
    }

    override suspend fun deleteDirect(sessionId: String) {
        directLists.removeAll { it.sessionId == sessionId }
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
                    CellRow(row.seq, it.id, it.startedAt, network, SurveyCell(cellX, cellY), row.localNew)
                }
            }
            .sortedWith(compareBy({ it.startedAt }, { it.sessionId }, { it.seq }))
    }

    override suspend fun setLocalNew(seq: Long, bits: Int) = measurements.replaceAll {
        if (it.seq == seq) it.copy(localNew = bits) else it
    }

    /** Saved answers by session and relay byte. */
    val savedAnswers = mutableMapOf<Pair<String, Int>, SavedAnswer>()

    override suspend fun saveAnswers(answers: List<SavedAnswer>) = answers.forEach {
        savedAnswers[it.sessionId to it.relayByte] = it
    }

    override suspend fun answerRows(sessionId: String) = savedAnswers.values
        .filter { it.sessionId == sessionId }
        .mapNotNull { answer ->
            measurements.find { it.seq == answer.seq }?.let { SavedAnswerRow(answer, it.heardAtMs) }
        }

    override suspend fun deleteAnswers(sessionId: String) {
        savedAnswers.keys.removeAll { it.first == sessionId }
    }

    override suspend fun clearAnswers() = savedAnswers.clear()

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
