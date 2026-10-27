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
 * reported position unless SigSleuth knows it reaches farther. A node heard directly 200 km away on a road trip did not
 * relay a packet heard here.
 */
const val FAR = 30_000.0

/**
 * How far a relayed packet's signal may exceed a candidate's strongest direct signal heard within [NEAR] before that
 * candidate is ruled out. A relay sends at its own power over its own path, so its copy cannot arrive much stronger
 * than its own packets heard here; weaker is normal (pocket, indoors, fading), so only "too strong" is checked. Set
 * 2026-10-22 on SigSleuth's loranet.pl gateways (279k relayed traceroute receptions): 30 dB wrongly ruled out 0.035% of
 * true relays and removed 15% of wrong directly heard candidates. Gateways don't move; ground level is untested.
 */
const val TOO_STRONG_DB = 30.0

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

/**
 * A reception's signal in dB: RSSI plus any negative SNR, since below the noise floor RSSI stops falling and the SNR
 * carries the rest. Null without RSSI; 0 is the proto's unset. A missing SNR counts as 0. `SurveyDao.directPlaces`
 * repeats this in SQL.
 */
fun signalOf(rssi: Int?, snr: Float?): Double? = rssi?.takeIf { it != 0 }?.let { it + minOf(snr ?: 0f, 0f).toDouble() }

/** This packet's [signalOf]. */
fun SurveyMeasurement.signal(): Double? = signalOf(rxRssi, rxSnr)

/** Which neighbour most likely relayed a packet. It is a guess, and the screen labels it as one. */
sealed interface RelayGuess {
    val nodeIds: List<Long>

    /** The packet came straight from [senderId]; nothing is guessed. */
    data class Direct(val senderId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(senderId)
    }

    /**
     * The only near neighbour with this byte not ruled out by the packet's signal, or the only recent one when the
     * packet has no fix.
     */
    data class Likely(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /**
     * None near (or recent without a fix), and the only candidate left: heard directly elsewhere within [FAR], or
     * placed within [FAR] or its reach ([RelayCandidate.Placed]). Without a fix, the only one heard directly in the
     * history.
     */
    data class Possible(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** Several neighbours in the best tier; nearest first, or newest first without a packet fix. */
    data class Ambiguous(override val nodeIds: List<Long>) : RelayGuess

    /** No candidate has this byte, or every one was ruled out by the packet's signal. */
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

/**
 * A directly heard node, its latest direct time, the latest direct time in each reception cell, and the strongest
 * [signalOf] in each cell that has one.
 */
data class DirectSender(
    val nodeId: Long,
    val lastDirectMs: Long,
    val places: Map<DirectCell, Long> = emptyMap(),
    val signals: Map<DirectCell, Double> = emptyMap(),
) {
    /** Closest cell centre heard since [sinceMs]; null when there are no cells in that history. */
    fun nearest(latitude: Double, longitude: Double, sinceMs: Long): NearestDirect? = places.entries
        .filter { it.value >= sinceMs }
        .map { (cell, at) -> NearestDirect(cell.metersFrom(latitude, longitude), at) }
        .minWithOrNull(compareBy<NearestDirect> { it.meters }.thenByDescending { it.heardAtMs })

    /** The strongest signal in cells within [meters] heard since [sinceMs]; null when none of them has one. */
    fun strongestWithin(latitude: Double, longitude: Double, meters: Double, sinceMs: Long): Double? = places.entries
        .filter { (cell, at) -> at >= sinceMs && cell.metersFrom(latitude, longitude) <= meters }
        .mapNotNull { signals[it.key] }
        .maxOrNull()
}

/** A node that could have relayed a packet, and how the survey knows of it. */
sealed interface RelayCandidate {
    val nodeId: Long

    /** Heard directly by the survey radio; where and when are in [DirectNeighbours.all]. */
    data class Heard(override val nodeId: Long) : RelayCandidate

    /**
     * Known by a position, which is a claim rather than a reception: a candidate within [FAR] of the packet, or within
     * its [reachMeters], the longest link SigSleuth trusts it to have sent, when that is longer.
     */
    sealed interface Placed : RelayCandidate {
        val latitude: Double
        val longitude: Double
        val reachMeters: Double?

        val withinMeters: Double
            get() = maxOf(FAR, reachMeters ?: 0.0)
    }

    /** Not heard directly, but the node database has it reporting this position. */
    data class Reported(
        override val nodeId: Long,
        override val latitude: Double,
        override val longitude: Double,
        override val reachMeters: Double? = null,
    ) : Placed

    /**
     * Neither heard directly nor in the node database, but on SigSleuth's map here; [estimated] when SigSleuth placed
     * it from its neighbours.
     */
    data class Mapped(
        override val nodeId: Long,
        override val latitude: Double,
        override val longitude: Double,
        override val reachMeters: Double?,
        val estimated: Boolean,
    ) : Placed
}

/** A node SigSleuth says could relay on the survey's spreading factor ([SurveyRelayNodes]). */
data class MappedNode(
    val nodeId: Long,
    val shortName: String?,
    val latitude: Double,
    val longitude: Double,
    val estimated: Boolean,
    val reachMeters: Double?,
)

/** SigSleuth's relay node list, fetched at [fetchedAtMs], by relay byte, with the short names it knows. */
class MappedNodes(val fetchedAtMs: Long, nodes: List<MappedNode>) {
    val count = nodes.size
    val byByte: Map<Int, List<MappedNode>> = nodes.groupBy { relayByteOf(it.nodeId) }
    val names: Map<Long, String> = nodes.mapNotNull { node -> node.shortName?.let { node.nodeId to it } }.toMap()
}

/**
 * SigSleuth's answer for one uploaded packet this phone thought relayed: the node it heard, or null when it could not
 * tell, and [how] it decided, in its own words (`survey_link`, `gateway_link`, `sender_link`, `reported`,
 * `only_candidate`, `direct`, `unknown`). Unknown relays may have [candidates], which remain possibilities.
 */
data class ServerAnswer(
    val seq: Long,
    val heardAtMs: Long,
    val relayByte: Int,
    val nodeId: Long?,
    val how: String,
    val shortName: String?,
    val candidates: List<ServerCandidate> = emptyList(),
)

/** A possible relay suggested by SigSleuth, at [km] from the uploaded packet's fix. */
data class ServerCandidate(val nodeId: Long, val name: String?, val km: Double)

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

/**
 * The [reportedWith] nodes, with their reach from [mapped], then the [mapped] nodes with [relayByte] the node database
 * lacks: the node's own report beats SigSleuth's copy, as this radio heard it.
 */
fun placedWith(
    relayByte: Int,
    nodes: Collection<Node>,
    mapped: MappedNodes?,
    sinceMs: Long,
    surveyNode: Int,
): List<RelayCandidate.Placed> {
    val onMap = mapped?.byByte?.get(relayByte).orEmpty().filter { it.nodeId != surveyNode.toUInt().toLong() }
    val reach = onMap.associate { it.nodeId to it.reachMeters }
    val reported = reportedWith(relayByte, nodes, sinceMs, surveyNode).map { it.copy(reachMeters = reach[it.nodeId]) }
    val inDatabase = reported.mapTo(mutableSetOf()) { it.nodeId }
    return reported +
        onMap
            .filter { it.nodeId !in inDatabase }
            .map { RelayCandidate.Mapped(it.nodeId, it.latitude, it.longitude, it.reachMeters, it.estimated) }
}

private val Node.nodeId: Long
    get() = num.toUInt().toLong()

/** A node heard directly near the packet whose copy arrived [packetSignal] dB, too strong for its [strongestHere]. */
data class RuledOut(val nodeId: Long, val strongestHere: Double, val packetSignal: Double)

/**
 * A relay byte's guess and every candidate behind it: the guessed tier first, the rest after it by distance. [ruledOut]
 * are the candidates the packet's signal excluded.
 */
data class RelayEvidence(
    val guess: RelayGuess,
    val candidates: List<RelayCandidate>,
    val ruledOut: List<RuledOut> = emptyList(),
)

/**
 * One relay byte in this session: its packets, when the last arrived, and the guess made for that packet with all its
 * candidates. A direct packet has none. [server] is SigSleuth's answer for the newest of its packets uploaded so far,
 * which is often an earlier one.
 */
data class RelayStats(
    val packets: Int,
    val lastHeardMs: Long,
    val guess: RelayGuess,
    val candidates: List<RelayCandidate> = emptyList(),
    val ruledOut: List<RuledOut> = emptyList(),
    val server: ServerAnswer? = null,
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
        val signal = row.signal()
        heard(
            DirectSender(
                nodeId = row.senderNodeId,
                lastDirectMs = row.heardAtMs,
                places = cell?.let { mapOf(it to row.heardAtMs) }.orEmpty(),
                signals = cell?.let { c -> signal?.let { mapOf(c to it) } }.orEmpty(),
            ),
        )
    }

    /**
     * Who relayed a packet from [senderId] with [relayByte], heard at [atMs]. The sender and known CLIENT_MUTE nodes
     * cannot be its relay; [roleOf] checks the survey radio's node database, and an unknown role keeps a candidate.
     * [placed] are the nodes with this byte known by a position ([placedWith]); with a packet fix, those within their
     * [RelayCandidate.Placed.withinMeters] join the neighbours heard directly elsewhere. A position is a claim, not a
     * reception, so it never makes a guess likely. With a fix and the packet's [signal], a neighbour whose strongest
     * direct signal within [NEAR] is more than [TOO_STRONG_DB] weaker is ruled out of every tier for this packet.
     */
    @Suppress("LongParameterList")
    fun weigh(
        relayByte: Int,
        atMs: Long,
        senderId: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        roleOf: (Long) -> Config.DeviceConfig.Role? = { null },
        placed: List<RelayCandidate.Placed> = emptyList(),
        signal: Double? = null,
    ): RelayEvidence {
        val sinceMs = atMs - DIRECT_HISTORY.inWholeMilliseconds
        val inPlay =
            byByte[relayByte]
                .orEmpty()
                .filter { it != senderId }
                .map { all.getValue(it) }
                .filter { it.lastDirectMs >= sinceMs }
                .filter { roleOf(it.nodeId) != Config.DeviceConfig.Role.CLIENT_MUTE }
        val recentMs = atMs - RECENT_DIRECT.inWholeMilliseconds
        if (latitude == null || longitude == null) {
            val newest = inPlay.sortedWith(compareByDescending<DirectSender> { it.lastDirectMs }.thenBy { it.nodeId })
            val recent = newest.filter { it.lastDirectMs >= recentMs }
            val tier = recent.ifEmpty { newest }
            return RelayEvidence(
                tierGuess(tier.map { it.nodeId }, likely = recent.isNotEmpty()),
                newest.map { RelayCandidate.Heard(it.nodeId) },
            )
        }
        // Only receptions within NEAR are compared: a node heard weakly far away may well be strong here.
        val ruledOut =
            if (signal == null) {
                emptyList()
            } else {
                inPlay.mapNotNull { candidate ->
                    candidate
                        .strongestWithin(latitude, longitude, NEAR, sinceMs)
                        ?.takeIf { signal - it > TOO_STRONG_DB }
                        ?.let { RuledOut(candidate.nodeId, it, signal) }
                }
            }
        val excluded = ruledOut.mapTo(mutableSetOf()) { it.nodeId }
        val candidates = inPlay.filter { it.nodeId !in excluded }
        // A neighbour without cells in the history has no distance; it stays, after every one that has.
        val heard = candidates.map { candidate ->
            Ranked(RelayCandidate.Heard(candidate.nodeId), candidate.nearest(latitude, longitude, sinceMs)?.meters)
        }
        val near = heard.filter { ranked ->
            ranked.meters?.let { it <= NEAR } ?: (all.getValue(ranked.candidate.nodeId).lastDirectMs >= recentMs)
        }
        val heardElsewhere = (heard - near.toSet()).filter { ranked -> ranked.meters?.let { it <= FAR } ?: true }
        // A node heard directly only far away can still be placed nearby.
        // A node ruled out by its signal must not come back by its position.
        val kept = (near + heardElsewhere).mapTo(mutableSetOf()) { it.candidate.nodeId }
        val placedNearby =
            placed
                .filter { it.nodeId != senderId && it.nodeId !in kept && it.nodeId !in excluded }
                .filter { roleOf(it.nodeId) != Config.DeviceConfig.Role.CLIENT_MUTE }
                .mapNotNull { node ->
                    latLongToMeter(node.latitude, node.longitude, latitude, longitude)
                        .takeIf { it <= node.withinMeters }
                        ?.let { Ranked(node, it) }
                }
        val byDistance = compareBy<Ranked> { it.meters ?: Double.POSITIVE_INFINITY }.thenBy { it.candidate.nodeId }
        val nearTier = near.sortedWith(byDistance).map { it.candidate }
        val possibleTier = (heardElsewhere + placedNearby).sortedWith(byDistance).map { it.candidate }
        val guess =
            if (nearTier.isNotEmpty()) {
                tierGuess(nearTier.map { it.nodeId }, likely = true)
            } else {
                tierGuess(possibleTier.map { it.nodeId }, likely = false)
            }
        return RelayEvidence(guess, nearTier + possibleTier, ruledOut)
    }

    /** A candidate with its distance from the packet; null for a neighbour without cells in the history. */
    private data class Ranked(val candidate: RelayCandidate, val meters: Double?)

    private fun tierGuess(nodeIds: List<Long>, likely: Boolean): RelayGuess = when {
        nodeIds.isEmpty() -> RelayGuess.Unknown
        nodeIds.size > 1 -> RelayGuess.Ambiguous(nodeIds)
        likely -> RelayGuess.Likely(nodeIds.single())
        else -> RelayGuess.Possible(nodeIds.single())
    }

    /**
     * Keeps the latest direct time for the node and for each cell, and each cell's strongest signal, without changing
     * published snapshots.
     */
    private fun heard(sender: DirectSender) {
        val known = all[sender.nodeId]
        val merged =
            if (known == null) {
                sender
            } else {
                known.copy(
                    lastDirectMs = maxOf(sender.lastDirectMs, known.lastDirectMs),
                    places = maxMerge(known.places, sender.places),
                    signals = maxMerge(known.signals, sender.signals),
                )
            }
        byByte.getOrPut(relayByteOf(sender.nodeId)) { mutableSetOf() } += sender.nodeId
        all = all + (sender.nodeId to merged)
    }
}

/** [old] and [new] together, keeping the larger value where both have a key. */
private fun <T : Comparable<T>> maxMerge(old: Map<DirectCell, T>, new: Map<DirectCell, T>): Map<DirectCell, T> =
    old.toMutableMap().apply { new.forEach { (cell, value) -> this[cell] = maxOf(value, this[cell] ?: value) } }.toMap()
