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
import org.meshtastic.core.model.Node
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

/**
 * The farthest a candidate can be from the packet and still be one: from the nearest direct reception cell, or from its
 * reported position. A node heard directly 200 km away on a road trip did not relay a packet heard here.
 */
const val FAR = 30_000.0

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

    /**
     * None near (or recent without a fix), and the only candidate left: heard directly elsewhere within [FAR], or
     * reporting a position within [FAR]. Without a fix, the only one heard directly in the history.
     */
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

/** A node that could have relayed a packet, and how the survey knows of it. */
sealed interface RelayCandidate {
    val nodeId: Long

    /** Heard directly by the survey radio; where and when are in [DirectNeighbours.all]. */
    data class Heard(override val nodeId: Long) : RelayCandidate

    /** Not heard directly, but the node database has it reporting this position. */
    data class Reported(override val nodeId: Long, val latitude: Double, val longitude: Double) : RelayCandidate
}

private const val MILLIS_PER_SECOND = 1_000L

/**
 * The [nodes] with [relayByte] that report a position and were last heard since [sinceMs], other than the survey radio
 * itself. A node the radio has not heard from in the history is unlikely to be relaying.
 */
fun reportedWith(
    relayByte: Int,
    nodes: Collection<Node>,
    sinceMs: Long,
    surveyNode: Int,
): List<RelayCandidate.Reported> = nodes
    .filter { relayByteOf(it.nodeId) == relayByte && it.num != surveyNode }
    .filter { it.lastHeard * MILLIS_PER_SECOND >= sinceMs && it.validPosition != null }
    .map { RelayCandidate.Reported(it.nodeId, it.latitude, it.longitude) }

private val Node.nodeId: Long
    get() = num.toUInt().toLong()

/** A relay byte's guess and every candidate behind it: the guessed tier first, the rest after it by distance. */
data class RelayEvidence(val guess: RelayGuess, val candidates: List<RelayCandidate>)

/**
 * One relay byte in this session: its packets, when the last arrived, and the guess made for that packet with all its
 * candidates. A direct packet has none.
 */
data class RelayStats(
    val packets: Int,
    val lastHeardMs: Long,
    val guess: RelayGuess,
    val candidates: List<RelayCandidate> = emptyList(),
)

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
     * [reported] are the database's nodes with this byte and a position; with a packet fix, those within [FAR] join the
     * neighbours heard directly elsewhere. A reported position is the node's claim, not a reception, so it never makes
     * a guess likely.
     */
    @Suppress("LongParameterList")
    fun weigh(
        relayByte: Int,
        atMs: Long,
        senderId: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        roleOf: (Long) -> Config.DeviceConfig.Role? = { null },
        reported: List<RelayCandidate.Reported> = emptyList(),
    ): RelayEvidence {
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
            val newest =
                candidates.sortedWith(compareByDescending<DirectSender> { it.lastDirectMs }.thenBy { it.nodeId })
            val recent = newest.filter { it.lastDirectMs >= recentMs }
            val tier = recent.ifEmpty { newest }
            return RelayEvidence(
                tierGuess(tier.map { it.nodeId }, likely = recent.isNotEmpty()),
                newest.map { RelayCandidate.Heard(it.nodeId) },
            )
        }
        // A neighbour without cells in the history has no distance; it stays, after every one that has.
        val heard = candidates.map { candidate ->
            Ranked(RelayCandidate.Heard(candidate.nodeId), candidate.nearest(latitude, longitude, sinceMs)?.meters)
        }
        val near = heard.filter { ranked ->
            ranked.meters?.let { it <= NEAR } ?: (all.getValue(ranked.candidate.nodeId).lastDirectMs >= recentMs)
        }
        val heardElsewhere = (heard - near.toSet()).filter { ranked -> ranked.meters?.let { it <= FAR } ?: true }
        // A node heard directly only far away can still report a position nearby.
        val kept = (near + heardElsewhere).mapTo(mutableSetOf()) { it.candidate.nodeId }
        val reportedNearby =
            reported
                .filter { it.nodeId != senderId && it.nodeId !in kept }
                .filter { roleOf(it.nodeId) != Config.DeviceConfig.Role.CLIENT_MUTE }
                .mapNotNull { node ->
                    latLongToMeter(node.latitude, node.longitude, latitude, longitude)
                        .takeIf { it <= FAR }
                        ?.let { Ranked(node, it) }
                }
        val byDistance = compareBy<Ranked> { it.meters ?: Double.POSITIVE_INFINITY }.thenBy { it.candidate.nodeId }
        val nearTier = near.sortedWith(byDistance).map { it.candidate }
        val possibleTier = (heardElsewhere + reportedNearby).sortedWith(byDistance).map { it.candidate }
        val guess =
            if (nearTier.isNotEmpty()) {
                tierGuess(nearTier.map { it.nodeId }, likely = true)
            } else {
                tierGuess(possibleTier.map { it.nodeId }, likely = false)
            }
        return RelayEvidence(guess, nearTier + possibleTier)
    }

    /** A candidate with its distance from the packet; null for a neighbour without cells in the history. */
    private data class Ranked(val candidate: RelayCandidate, val meters: Double?)

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
