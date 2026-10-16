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

/** A node the survey radio heard directly, and when it last did. */
data class DirectSender(val nodeId: Long, val lastDirectMs: Long)

/**
 * The nodes the survey radio has heard directly, by relay byte, with when each was last heard so. Only these are
 * candidates for a relay: a node anywhere in the node database could share the byte, which is why upstream dropped its
 * whole-database guess (meshtastic/Meshtastic-Android#3773). Not thread-safe; only the recorder's writer uses it.
 */
class DirectNeighbours(history: List<DirectSender> = emptyList()) {
    private val byByte = mutableMapOf<Int, MutableMap<Long, Long>>()

    init {
        history.forEach { heard(it.nodeId, it.lastDirectMs) }
    }

    /** Remembers [row]'s sender if the packet came straight from it. */
    fun record(row: SurveyMeasurement) {
        if (row.isDirect()) heard(row.senderNodeId, row.heardAtMs)
    }

    /** Who relayed a packet from [senderId] with [relayByte], heard at [atMs]. A node never relays its own packet. */
    fun guess(relayByte: Int, atMs: Long, senderId: Long): RelayGuess {
        val heard = byByte[relayByte].orEmpty().filterKeys { it != senderId }
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

    private fun heard(nodeId: Long, atMs: Long) {
        val nodes = byByte.getOrPut(relayByteOf(nodeId)) { mutableMapOf() }
        nodes[nodeId] = maxOf(atMs, nodes[nodeId] ?: atMs)
    }
}
