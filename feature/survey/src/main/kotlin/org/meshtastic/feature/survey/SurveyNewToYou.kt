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

import androidx.room3.Embedded
import androidx.room3.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import kotlin.math.abs

/** What a measurement counts as within its cell: its relay byte, and its sender when heard directly ([isDirect]). */
data class CellKey(val relayByte: Int?, val directSender: Long?)

fun SurveyMeasurement.cellKey() = CellKey(relayByte, senderNodeId.takeIf { isDirect() })

fun SurveyMeasurement.cell(): SurveyCell? = cellX?.let { x -> cellY?.let { y -> SurveyCell(x, y) } }

/**
 * A session's radio settings. Sessions on the [same] network share cells: SigSleuth's rule (`radio.same_settings`).
 * Frequencies within half a bandwidth share a slot, as the app's value can differ by the node's frequency offset; an
 * unknown one matches any. Channels on one preset are told apart by frequency.
 */
data class SurveyNetwork(val spreadingFactor: Int, val bandwidthKhz: Float, val frequencyMhz: Float?) {
    fun same(other: SurveyNetwork): Boolean {
        val apart = frequencyMhz?.let { mine -> other.frequencyMhz?.let { abs(mine - it) } }
        return spreadingFactor == other.spreadingFactor &&
            bandwidthKhz == other.bandwidthKhz &&
            (apart == null || apart < bandwidthKhz / 2 / KHZ_PER_MHZ)
    }

    private companion object {
        const val KHZ_PER_MHZ = 1_000
    }
}

/** What a set of sessions heard in one cell on one network. */
class CellHistory(keys: Iterable<CellKey> = emptyList()) {
    private var measured = false
    private val bytes = mutableSetOf<Int>()
    private val directSenders = mutableSetOf<Long>()

    init {
        keys.forEach(::add)
    }

    fun add(key: CellKey) {
        measured = true
        key.relayByte?.let(bytes::add)
        key.directSender?.let(directSenders::add)
    }

    /** The [SurveyNews] bits [key] adds to this history. */
    fun newBits(key: CellKey): Int = (if (measured) 0 else SurveyNews.NEW_CELL) or
        (if (key.relayByte != null && key.relayByte !in bytes) SurveyNews.NEW_NODE else 0) or
        (if (key.directSender != null && key.directSender !in directSenders) SurveyNews.NEW_DIRECT else 0)
}

/** A saved measurement with a cell, and its session's start and network ([CELL_ROWS]). */
data class CellRow(
    val seq: Long,
    val sessionId: String,
    val startedAt: Long,
    @Embedded val network: SurveyNetwork,
    @Embedded val cell: SurveyCell,
    @Embedded val key: CellKey,
    val localNew: Int?,
)

/**
 * Each row with its [SurveyMeasurement.localNew], for rows in start order ([START_ORDER]): what it adds to the sessions
 * that started earlier on the [SurveyNetwork.same] network, as [NewToYou] works it out while recording.
 */
fun Sequence<CellRow>.withLocalNew(): Sequence<Pair<CellRow, Int>> = sequence {
    // What the sessions that started before the current start heard, by exact network. A row compares with each network
    // that is the [SurveyNetwork.same] as its own, as [SurveyDao.cellKeys] compares with each earlier session: an
    // unknown frequency matches two channels that don't match each other.
    val earlier = mutableMapOf<SurveyNetwork, MutableMap<SurveyCell, CellHistory>>()
    // Sessions that started at the same time, like one session's own rows, don't count against each other.
    val current = mutableListOf<CellRow>()
    var startedAt: Long? = null
    var network: SurveyNetwork? = null
    var matching = emptyList<Map<SurveyCell, CellHistory>>()
    for (row in this@withLocalNew) {
        if (row.startedAt != startedAt) {
            current.forEach {
                earlier.getOrPut(it.network, ::mutableMapOf).getOrPut(it.cell, ::CellHistory).add(it.key)
            }
            current.clear()
            startedAt = row.startedAt
            network = null
        }
        if (row.network != network) {
            network = row.network
            matching = earlier.filterKeys { it.same(row.network) }.values.toList()
        }
        // New only if new to every matching network.
        val bits =
            matching
                .mapNotNull { it[row.cell] }
                .fold(CellHistory().newBits(row.key)) { bits, history ->
                    bits and history.newBits(row.key)
                }
        current += row
        yield(row to bits)
    }
}

/**
 * A recording session's [SurveyMeasurement.localNew] and its live [SurveyNews]. [earlier] loads what earlier sessions
 * on the same network heard in a cell, once per cell: they don't change while this one records.
 */
internal class NewToYou(private val earlier: suspend (SurveyCell) -> List<CellKey>) {
    private val histories = mutableMapOf<SurveyCell, CellHistory>()
    private val seenCells = mutableSetOf<SurveyCell>()
    private val seenNodes = mutableSetOf<Pair<SurveyCell, Int>>()
    private val seenDirect = mutableSetOf<Pair<SurveyCell, Long>>()

    /** This session's keys so far and those new to the phone; no server counts. */
    var news = SurveyNews(cells = 0, nodes = 0, direct = 0)
        private set

    suspend fun bits(row: SurveyMeasurement): Int? {
        val cell = row.cell() ?: return null
        return histories.getOrPut(cell) { CellHistory(earlier(cell)) }.newBits(row.cellKey())
    }

    /**
     * Counts a saved row, with its [SurveyMeasurement.localNew] set. A key's rows share their bits; the first counts.
     */
    fun count(row: SurveyMeasurement) {
        val cell = row.cell() ?: return
        val key = row.cellKey()
        val bits = row.localNew ?: 0
        val cellAdded = seenCells.add(cell)
        val node = key.relayByte != null && seenNodes.add(cell to key.relayByte)
        val direct = key.directSender != null && seenDirect.add(cell to key.directSender)
        fun new(added: Boolean, bit: Int) = (added && bits and bit != 0).count()
        news =
            news.copy(
                cells = news.cells + cellAdded.count(),
                nodes = news.nodes + node.count(),
                direct = news.direct + direct.count(),
                localNewCells = news.localNewCells + new(cellAdded, SurveyNews.NEW_CELL),
                localNewNodes = news.localNewNodes + new(node, SurveyNews.NEW_NODE),
                localNewDirect = news.localNewDirect + new(direct, SurveyNews.NEW_DIRECT),
            )
    }
}

private fun Boolean.count() = if (this) 1 else 0

/** Version 5. Sessions go in start order, because a row is new only against sessions that started earlier. */
class FillNewToYou : AutoMigrationSpec {
    override suspend fun onPostMigrate(connection: SQLiteConnection) {
        fillCells(connection)
        fillLocalNew(connection)
        connection.execSQL(
            "UPDATE survey_session SET (news_cells, news_nodes, news_direct, news_new_cells, news_new_nodes, " +
                "news_new_direct, news_local_new_cells, news_local_new_nodes, news_local_new_direct) = " +
                "(SELECT $NEWS_COLUMNS FROM survey_measurement " +
                "WHERE session_id = survey_session.id AND cell_x IS NOT NULL)",
        )
    }

    @Suppress("MagicNumber") // Column and parameter indices.
    private fun fillCells(connection: SQLiteConnection) {
        val cells = mutableListOf<Pair<Long, SurveyCell>>()
        connection
            .prepare(
                "SELECT seq, latitude, longitude, accuracy_m, fix_age_ms FROM survey_measurement " +
                    "WHERE cell_x IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL",
            )
            .use { row ->
                while (row.step()) {
                    val accuracy = if (row.isNull(3)) null else row.getDouble(3).toFloat()
                    val fixAge = if (row.isNull(4)) 0 else row.getLong(4)
                    sigSleuthCell(row.getDouble(1), row.getDouble(2), accuracy, fixAge)?.let {
                        cells += row.getLong(0) to it
                    }
                }
            }
        connection.prepare("UPDATE survey_measurement SET cell_x = ?, cell_y = ? WHERE seq = ?").use { update ->
            for ((seq, cell) in cells) {
                update.bindLong(1, cell.x.toLong())
                update.bindLong(2, cell.y.toLong())
                update.bindLong(3, seq)
                update.step()
                update.reset()
            }
        }
    }

    private fun fillLocalNew(connection: SQLiteConnection) {
        val flags = mutableListOf<Pair<Long, Int>>()
        connection.prepare("$CELL_ROWS$START_ORDER").use { statement ->
            generateSequence { if (statement.step()) statement.cellRow() else null }
                .withLocalNew()
                .forEach { (row, bits) -> if (bits != 0) flags += row.seq to bits }
        }
        connection.execSQL("UPDATE survey_measurement SET local_new = 0 WHERE cell_x IS NOT NULL")
        connection.prepare("UPDATE survey_measurement SET local_new = ? WHERE seq = ?").use { update ->
            for ((seq, bits) in flags) {
                update.bindLong(1, bits.toLong())
                update.bindLong(2, seq)
                update.step()
                update.reset()
            }
        }
    }

    /** The current row of a [CELL_ROWS] query. */
    @Suppress("MagicNumber") // Column indices.
    private fun SQLiteStatement.cellRow() =
        CellRow(
            seq = getLong(0),
            sessionId = getText(1),
            startedAt = getLong(2),
            network =
            SurveyNetwork(
                getLong(3).toInt(),
                getDouble(4).toFloat(),
                if (isNull(5)) null else getDouble(5).toFloat(),
            ),
            cell = SurveyCell(getLong(6).toInt(), getLong(7).toInt()),
            key = CellKey(if (isNull(8)) null else getLong(8).toInt(), if (isNull(9)) null else getLong(9)),
            localNew = if (isNull(10)) null else getLong(10).toInt(),
        )
}

/**
 * The Sessions card: what every session on this phone was first to have, so each distinct cell, relay byte in a cell
 * and direct sender in a cell per network, and the cells they added to SigSleuth's map, deleted sessions' included
 * ([mapCells], null before any reply). Null while there is nothing to show.
 */
data class SurveyTotals(val cells: Int, val nodes: Int, val direct: Int, val mapCells: Int?)

fun surveyTotals(sessions: List<SurveySession>, deleted: SurveyDeleted?): SurveyTotals? {
    val news = sessions.mapNotNull { it.news }
    val answered = news.mapNotNull { it.newCells }
    val mapCells = (answered.sum() + (deleted?.newCells ?: 0)).takeIf { answered.isNotEmpty() || deleted != null }
    return SurveyTotals(
        news.sumOf { it.localNewCells },
        news.sumOf { it.localNewNodes },
        news.sumOf { it.localNewDirect },
        mapCells,
    )
        .takeIf { it.cells > 0 || mapCells != null }
}
