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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

/** Which neighbour most likely relayed a packet. It is a guess: the relays list shows the evidence under it. */
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
     * placed within [FAR] or its reach ([RelayCandidate.Placed]). Without a fix, the only one heard directly in the
     * history.
     */
    data class Possible(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** Several neighbours in the best tier; nearest first, or newest first without a packet fix. */
    data class Ambiguous(override val nodeIds: List<Long>) : RelayGuess

    /** No candidate has this byte. */
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

/** A node SigSleuth says could relay on the survey's spreading factor and bandwidth ([SurveyRelayNodes]). */
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
    val byByte: Map<Int, List<MappedNode>> = nodes.groupBy { relayByteOf(it.nodeId) }
    val names: Map<Long, String> = nodes.mapNotNull { node -> node.shortName?.let { node.nodeId to it } }.toMap()
}

/**
 * SigSleuth's answer for one uploaded packet this phone thought relayed: the node it heard, or null when it could not
 * tell, and [how] it decided, in its own words (`survey_link`, `gateway_link`, `near_link`, `sender_link`, `reported`,
 * `only_candidate`, `direct`, `unknown`). Unknown relays may have [candidates], which remain possibilities. A survey
 * link may have its [evidence], a near link its [near]; older servers send neither.
 */
data class ServerAnswer(
    val seq: Long,
    val heardAtMs: Long,
    val relayByte: Int,
    val nodeId: Long?,
    val how: String,
    val shortName: String?,
    val candidates: List<ServerCandidate> = emptyList(),
    val evidence: LinkEvidence? = null,
    val near: NearEvidence? = null,
)

/**
 * The direct reception behind a survey link that is closest in time to the packet: [km] from the packet's fix, its time
 * minus the packet's ([offsetMs], negative before), whether this survey radio heard it ([mine]) rather than another
 * survey's, and its [rssi] in dBm (null when not recorded, or from an older server).
 */
data class LinkEvidence(val km: Double, val offsetMs: Long, val mine: Boolean, val rssi: Int? = null)

/**
 * The node behind a near link: the one near the packet's fix that SigSleuth most often saw linked directly with the
 * relay, [km] from the fix, with the [name] SigSleuth gave (null when it has none).
 */
data class NearEvidence(val nodeId: Long, val name: String?, val km: Double)

/**
 * A possible relay suggested by SigSleuth, at [km] from the uploaded packet's fix. Serializable, as [SavedAnswer] keeps
 * a list of them as JSON.
 */
@Serializable data class ServerCandidate(@SerialName("node_id") val nodeId: Long, val name: String?, val km: Double)

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

/** A relay byte's guess and every candidate behind it: the guessed tier first, the rest after it by distance. */
data class RelayEvidence(val guess: RelayGuess, val candidates: List<RelayCandidate>)

/**
 * One relay byte in this session: its packets, the [last] of them, and the guess made for that packet with all its
 * candidates. A direct packet has none. [server] is SigSleuth's answer for the newest of its packets uploaded so far,
 * which is often an earlier one.
 */
data class RelayStats(
    val packets: Int,
    val last: SurveyMeasurement,
    val guess: RelayGuess,
    val candidates: List<RelayCandidate> = emptyList(),
    val server: ServerAnswer? = null,
) {
    val lastHeardMs: Long
        get() = last.heardAtMs
}

/**
 * These stats with one more packet, [row], and the guess made for it ([evidenceFor]). SigSleuth's answer stays;
 * [server] is the one to take when there is none yet.
 */
fun RelayStats?.withPacket(row: SurveyMeasurement, evidence: RelayEvidence, server: ServerAnswer? = null) = RelayStats(
    (this?.packets ?: 0) + 1,
    row,
    evidence.guess,
    evidence.candidates,
    this?.server ?: server,
)

/**
 * The guess for [row]'s relay byte, made before [row] is [DirectNeighbours.record]ed so a packet is never its own
 * evidence: its sender for a direct packet, else [DirectNeighbours.weigh] with the roles in [nodes], the survey radio's
 * node database, and the nodes [placedWith] places. Null without a relay byte.
 */
fun DirectNeighbours.evidenceFor(
    row: SurveyMeasurement,
    nodes: Map<Int, Node>,
    mapped: MappedNodes?,
    surveyNode: Int,
): RelayEvidence? {
    val byte = row.relayByte ?: return null
    return if (row.isDirect()) {
        RelayEvidence(RelayGuess.Direct(row.senderNodeId), emptyList())
    } else {
        // Positions only count from a packet fix, so a fixless packet skips the scan.
        val sinceMs = row.heardAtMs - DIRECT_HISTORY.inWholeMilliseconds
        val placed = row.latitude?.let { placedWith(byte, nodes.values, mapped, sinceMs, surveyNode) }.orEmpty()
        weigh(
            byte,
            row.heardAtMs,
            row.senderNodeId,
            row.latitude,
            row.longitude,
            roleOf = { id -> nodes[id.toInt()]?.user?.role },
            placed = placed,
        )
    }
}

/**
 * The nodes the survey radio has heard directly, by relay byte, with when each was last heard so. Only these are
 * candidates for a relay: a node anywhere in the node database could share the byte, which is why upstream dropped its
 * whole-database guess (meshtastic/Meshtastic-Android#3773). Not thread-safe: one writer each, the recorder's or a
 * replay's ([replayRelays]).
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
     * [placed] are the nodes with this byte known by a position ([placedWith]); with a packet fix, those within their
     * [RelayCandidate.Placed.withinMeters] join the neighbours heard directly elsewhere. A position is a claim, not a
     * reception, so it never makes a guess likely.
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
        // A neighbour without cells in the history has no distance; it stays, after every one that has.
        val heard = inPlay.map { candidate ->
            Ranked(RelayCandidate.Heard(candidate.nodeId), candidate.nearest(latitude, longitude, sinceMs)?.meters)
        }
        val near = heard.filter { ranked ->
            ranked.meters?.let { it <= NEAR } ?: (all.getValue(ranked.candidate.nodeId).lastDirectMs >= recentMs)
        }
        val heardElsewhere = (heard - near.toSet()).filter { ranked -> ranked.meters?.let { it <= FAR } ?: true }
        // A node heard directly only far away can still be placed nearby.
        val kept = (near + heardElsewhere).mapTo(mutableSetOf()) { it.candidate.nodeId }
        val placedNearby =
            placed
                .filter { it.nodeId != senderId && it.nodeId !in kept }
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

    /** Keeps the latest direct time for the node and for each cell without changing published snapshots. */
    private fun heard(sender: DirectSender) {
        val known = all[sender.nodeId]
        val merged =
            if (known == null) {
                sender
            } else {
                known.copy(
                    lastDirectMs = maxOf(sender.lastDirectMs, known.lastDirectMs),
                    places = maxMerge(known.places, sender.places),
                )
            }
        byByte.getOrPut(relayByteOf(sender.nodeId)) { mutableSetOf() } += sender.nodeId
        all = all + (sender.nodeId to merged)
    }
}

/** [old] and [new] together, keeping the larger value where both have a key. */
private fun <T : Comparable<T>> maxMerge(old: Map<DirectCell, T>, new: Map<DirectCell, T>): Map<DirectCell, T> =
    old.toMutableMap().apply { new.forEach { (cell, value) -> this[cell] = maxOf(value, this[cell] ?: value) } }.toMap()
