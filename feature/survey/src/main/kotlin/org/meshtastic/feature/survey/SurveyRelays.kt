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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** A neighbour heard directly within this long counts as recent, and a guess from it as likely. */
val RECENT_DIRECT = 1.hours

/** How far back the survey radio's earlier sessions are searched for neighbours heard directly. */
val DIRECT_HISTORY = 30.days

private const val BYTE_MASK = 0xFFL
private const val ZERO_BYTE_RELAY = 0xFF

/**
 * The relay byte a node puts on the packets it sends or relays: the last byte of its node number, where `00` becomes
 * `FF` (`getLastByteOfNodeNum` in the firmware's `NodeDB.h`). So `FF` can mean a node ending in `00` or in `ff`.
 */
fun relayByteOf(nodeId: Long): Int = (nodeId and BYTE_MASK).toInt().takeIf { it != 0 } ?: ZERO_BYTE_RELAY

/**
 * Whether the survey radio heard this packet straight from its sender: no hop used, and the relay byte is the sender's
 * own. `SurveyDao.directSenders` repeats this test in SQL.
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

    /** The only neighbour with this byte heard directly within [RECENT_DIRECT]. */
    data class Likely(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** None recent, and the only one with this byte in the older history. */
    data class Possible(val nodeId: Long) : RelayGuess {
        override val nodeIds
            get() = listOf(nodeId)
    }

    /** Several neighbours share this byte in the best tier that has any; newest first. */
    data class Ambiguous(override val nodeIds: List<Long>) : RelayGuess

    /** No neighbour heard directly has this byte. */
    data object Unknown : RelayGuess {
        override val nodeIds
            get() = emptyList<Long>()
    }
}

/**
 * A node the survey radio heard directly, and when it last did. The position is where the phone was the last time it
 * heard the node directly with a GPS fix, at [positionMs], which can be older than [lastDirectMs]; null when no direct
 * reception had a fix. It is not the node's own position.
 */
data class DirectSender(
    val nodeId: Long,
    val lastDirectMs: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val positionMs: Long? = null,
) {
    /** Metres from where it was last heard directly to ([latitude], [longitude]); null without a position. */
    fun metersFrom(latitude: Double, longitude: Double): Double? {
        val lat = this.latitude
        val lon = this.longitude
        return if (lat == null || lon == null) null else latLongToMeter(lat, lon, latitude, longitude)
    }
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
        val positioned = row.latitude != null && row.longitude != null
        heard(
            DirectSender(
                nodeId = row.senderNodeId,
                lastDirectMs = row.heardAtMs,
                latitude = row.latitude,
                longitude = row.longitude,
                positionMs = row.heardAtMs.takeIf { positioned },
            ),
        )
    }

    /**
     * Who relayed a packet from [senderId] with [relayByte], heard at [atMs]. The sender and known CLIENT_MUTE nodes
     * cannot be its relay; [roleOf] checks the survey radio's node database, and an unknown role keeps a candidate.
     */
    fun guess(
        relayByte: Int,
        atMs: Long,
        senderId: Long,
        roleOf: (Long) -> Config.DeviceConfig.Role? = { null },
    ): RelayGuess {
        val heard =
            byByte[relayByte]
                .orEmpty()
                .filter { it != senderId && roleOf(it) != Config.DeviceConfig.Role.CLIENT_MUTE }
                .associateWith { all.getValue(it).lastDirectMs }
        val recent = heard.filterValues { it >= atMs - RECENT_DIRECT.inWholeMilliseconds }
        val older = heard.filterValues { it >= atMs - DIRECT_HISTORY.inWholeMilliseconds }
        val tier = recent.ifEmpty { older }
        return when {
            tier.isEmpty() -> RelayGuess.Unknown
            tier.size > 1 -> RelayGuess.Ambiguous(tier.entries.sortedByDescending { it.value }.map { it.key })
            recent.isNotEmpty() -> RelayGuess.Likely(tier.keys.single())
            else -> RelayGuess.Possible(tier.keys.single())
        }
    }

    /** Keeps the latest direct time, and the position of whichever reception has the newer one. */
    private fun heard(sender: DirectSender) {
        val known = all[sender.nodeId]
        val merged =
            if (known == null) {
                sender
            } else {
                val place = if ((sender.positionMs ?: -1) > (known.positionMs ?: -1)) sender else known
                place.copy(lastDirectMs = maxOf(sender.lastDirectMs, known.lastDirectMs))
            }
        byByte.getOrPut(relayByteOf(sender.nodeId)) { mutableSetOf() } += sender.nodeId
        all = all + (sender.nodeId to merged)
    }
}
