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

import org.meshtastic.core.common.util.latLongToMeter
import org.meshtastic.proto.Config
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Time fallback for packets without a fix, or candidates without reception cells. */
val RECENT_DIRECT = 1.hours

/** How far back the survey radio's earlier sessions are searched for neighbours heard directly. */
val DIRECT_HISTORY = 30.days

/** Within 1 km of a direct reception cell counts as near, allowing for cell size and GPS error. */
const val NEAR = 1_000.0

private const val BYTE_MASK = 0xFFL
private const val ZERO_BYTE_RELAY = 0xFF

/**
 * The relay byte a node puts on the packets it sends or relays: the last byte of its node number, where `00` becomes
 * `FF` (`getLastByteOfNodeNum` in the firmware's `NodeDB.h`). So `FF` can mean a node ending in `00` or in `ff`.
 */
fun relayByteOf(nodeId: Long): Int = (nodeId and BYTE_MASK).toInt().takeIf { it != 0 } ?: ZERO_BYTE_RELAY

/**
 * Whether the survey radio heard this packet straight from its sender: no hop used, and the relay byte is the sender's
 * own. `SurveyDao.directPlaces` repeats this test in SQL.
 */
fun SurveyMeasurement.isDirect(): Boolean =
    relayByte != null && hopStart == hopLimit && relayByte == relayByteOf(senderNodeId)

/** Which neighbour most likely relayed a packet. It is a guess, and the screen labels it as one. */
sealed interface RelayGuess {
    val nodeIds: List<Long>

    /** The packet came straight from [senderId]; nothing is guessed. */
    data class Direct(val senderId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(senderId)
    }

    /** The only near neighbour with this byte, or the only recent one when the packet has no fix. */
    data class Likely(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** None near (or recent without a fix), and the only one with this byte in the history. */
    data class Possible(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** Several neighbours in the best tier; nearest first, or newest first without a packet fix. */
    data class Ambiguous(override val nodeIds: List<Long>) : RelayGuess

    /** No neighbour heard directly has this byte. */
    data object Unknown : RelayGuess {
        override val nodeIds
            get() = emptyList<Long>()
    }
}

private const val CELLS_PER_DEGREE = 500.0

/** A 0.002° cell where the phone heard a direct reception, not the heard node's position. */
data class DirectCell(val latitude: Int, val longitude: Int) {
    fun metersFrom(latitude: Double, longitude: Double): Double =
        latLongToMeter(this.latitude / CELLS_PER_DEGREE, this.longitude / CELLS_PER_DEGREE, latitude, longitude)

    companion object {
        fun of(latitude: Double, longitude: Double): DirectCell = DirectCell(cell(latitude), cell(longitude))

        // SQLite ROUND rounds halves away from zero; roundToInt alone rounds negative halves towards zero.
        private fun cell(coordinate: Double): Int {
            val scaled = coordinate * CELLS_PER_DEGREE
            return if (scaled < 0) -(-scaled).roundToInt() else scaled.roundToInt()
        }
    }
}

/** The closest direct reception's distance from the requested position and its latest time in that cell. */
data class NearestDirect(val meters: Double, val heardAtMs: Long)

/** A directly heard node, its latest direct time, and the latest direct time in each reception cell. */
data class DirectSender(val nodeId: Long, val lastDirectMs: Long, val places: Map<DirectCell, Long> = emptyMap()) {
    /** Closest cell centre heard since [sinceMs]; null when there are no cells in that history. */
    fun nearest(latitude: Double, longitude: Double, sinceMs: Long): NearestDirect? = places.entries
        .filter { it.value >= sinceMs }
        .map { (cell, at) -> NearestDirect(cell.metersFrom(latitude, longitude), at) }
        .minWithOrNull(compareBy<NearestDirect> { it.meters }.thenByDescending { it.heardAtMs })
}

/** One relay byte in this session: its packets, when the last arrived, and the guess made for that packet. */
data class RelayStats(val packets: Int, val lastHeardMs: Long, val guess: RelayGuess)

/**
 * The nodes the survey radio has heard directly, by relay byte, with when each was last heard so. Only these are
 * candidates for a relay: a node anywhere in the node database could share the byte, which is why upstream dropped its
 * whole-database guess (meshtastic/Meshtastic-Android#3773). Not thread-safe; only the recorder's writer uses it.
 */
class DirectNeighbours(history: List<DirectSender> = emptyList()) {
    private val byByte = mutableMapOf<Int, MutableSet<Long>>()

    /** Every node heard directly, by node ID. Replaced on each change rather than changed, so it can be shared. */
    var all: Map<Long, DirectSender> = emptyMap()
        private set

    init {
        history.forEach(::heard)
    }

    /** Remembers [row]'s sender if the packet came straight from it. */
    fun record(row: SurveyMeasurement) {
        if (!row.isDirect()) return
        val cell = row.latitude?.let { lat -> row.longitude?.let { lon -> DirectCell.of(lat, lon) } }
        heard(
            DirectSender(
                nodeId = row.senderNodeId,
                lastDirectMs = row.heardAtMs,
                places = cell?.let { mapOf(it to row.heardAtMs) }.orEmpty(),
            ),
        )
    }

    /**
     * Who relayed a packet from [senderId] with [relayByte], heard at [atMs]. The sender and known CLIENT_MUTE nodes
     * cannot be its relay; [roleOf] checks the survey radio's node database, and an unknown role keeps a candidate.
     */
    @Suppress("LongParameterList")
    fun guess(
        relayByte: Int,
        atMs: Long,
        senderId: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        roleOf: (Long) -> Config.DeviceConfig.Role? = { null },
    ): RelayGuess {
        val sinceMs = atMs - DIRECT_HISTORY.inWholeMilliseconds
        val candidates =
            byByte[relayByte]
                .orEmpty()
                .filter { it != senderId }
                .map { all.getValue(it) }
                .filter { it.lastDirectMs >= sinceMs }
                .filter { roleOf(it.nodeId) != Config.DeviceConfig.Role.CLIENT_MUTE }
        val recentMs = atMs - RECENT_DIRECT.inWholeMilliseconds
        if (latitude == null || longitude == null) {
            val recent = candidates.filter { it.lastDirectMs >= recentMs }
            val tier =
                recent
                    .ifEmpty { candidates }
                    .sortedWith(compareByDescending<DirectSender> { it.lastDirectMs }.thenBy { it.nodeId })
            return tierGuess(tier.map { it.nodeId }, likely = recent.isNotEmpty())
        }
        val distances = candidates.associate { it.nodeId to it.nearest(latitude, longitude, sinceMs) }
        val ordered =
            candidates.sortedWith(
                compareBy<DirectSender> { distances[it.nodeId]?.meters ?: Double.POSITIVE_INFINITY }
                    .thenBy { it.nodeId },
            )
        val near = ordered.filter { candidate ->
            distances[candidate.nodeId]?.let { it.meters <= NEAR } ?: (candidate.lastDirectMs >= recentMs)
        }
        return tierGuess(near.ifEmpty { ordered }.map { it.nodeId }, likely = near.isNotEmpty())
    }

    private fun tierGuess(nodeIds: List<Long>, likely: Boolean): RelayGuess = when {
        nodeIds.isEmpty() -> RelayGuess.Unknown
        nodeIds.size > 1 -> RelayGuess.Ambiguous(nodeIds)
        likely -> RelayGuess.Likely(nodeIds.single())
        else -> RelayGuess.Possible(nodeIds.single())
    }

    /** Keeps the latest direct time for the node and for each cell, without changing published snapshots. */
    private fun heard(sender: DirectSender) {
        val known = all[sender.nodeId]
        val merged =
            if (known == null) {
                sender
            } else {
                val places = known.places.toMutableMap()
                sender.places.forEach { (cell, at) -> places[cell] = maxOf(at, places[cell] ?: at) }
                known.copy(lastDirectMs = maxOf(sender.lastDirectMs, known.lastDirectMs), places = places.toMap())
            }
        byByte.getOrPut(relayByteOf(sender.nodeId)) { mutableSetOf() } += sender.nodeId
        all = all + (sender.nodeId to merged)
    }
}
