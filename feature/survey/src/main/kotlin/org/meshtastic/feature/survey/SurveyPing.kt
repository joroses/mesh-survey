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

import org.koin.core.annotation.Single
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.repository.CommandSender
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.SurveyPacketTap
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.RouteDiscovery
import kotlin.time.Duration.Companion.seconds

/**
 * Firmware refuses a second traceroute from the phone within this time of taking the last (PhoneAPI's rate limit),
 * whether the survey or the app's own traceroute sent it.
 */
val FIRMWARE_TRACEROUTE_LIMIT = 30.seconds

/**
 * The time between pings. [FIRMWARE_TRACEROUTE_LIMIT] runs from when the radio took the last ping, which may have
 * waited in the app's queue, so a little longer. A traceroute the app sent itself can still get a ping refused
 * ([SurveyPing.refused]).
 */
val PING_COOLDOWN = FIRMWARE_TRACEROUTE_LIMIT + 2.seconds

/**
 * A traceroute to everyone that no node passes on. Each node that hears it answers once, straight back with zero hops
 * (firmware 2.5.23 and later ignore a broadcast traceroute that was relayed), so every answer comes from a node in
 * radio range both ways, with the SNR at which it heard the survey node. Firmware refuses a broadcast traceroute from
 * the phone with a hop limit above zero, and fills in its own hop limit only when an ACK is wanted, so there is none:
 * the answers are the acknowledgement. 22 bytes on air; each answer about 30 to 39.
 */
fun zeroHopTraceroute(id: Int): MeshPacket = MeshPacket.Builder()
    .apply {
        to = NodeAddress.NODENUM_BROADCAST
        this.id = id
        want_ack = false
        hop_limit = 0
        hop_start = 0
        channel = 0
        decoded =
            Data.Builder()
                .apply {
                    portnum = PortNum.TRACEROUTE_APP
                    want_response = true
                    payload = RouteDiscovery.Builder().build().encodeByteString()
                }
                .build()
    }
    .build()

/** Sends the survey's ping ([zeroHopTraceroute]). */
fun interface SurveyPingSender {
    /** The ping's ID when the packet queue took it, else null; firmware may still refuse it ([SurveyPing.refused]). */
    suspend fun send(): Int?
}

/**
 * Sends through the app's packet queue, as the app's own traceroute does, but not through its hop-limit default. The
 * ping's ID goes on the [tap] first, so the app shows no traceroute result for the answers.
 */
@Single(binds = [SurveyPingSender::class])
class RadioSurveyPingSender(
    private val packets: PacketHandler,
    private val commands: CommandSender,
    private val tap: SurveyPacketTap,
) : SurveyPingSender {
    override suspend fun send(): Int? {
        val id = commands.generatePacketId()
        // A few, for answers that arrive after the next ping.
        tap.pingIds = (tap.pingIds.toList() + id).takeLast(KEPT_PING_IDS).toSet()
        return id.takeIf { packets.sendToRadio(zeroHopTraceroute(id)) }
    }

    private companion object {
        const val KEPT_PING_IDS = 4
    }
}

/** A node that answered the ping: how the survey node heard it, and how it heard the survey node. */
data class PingAnswer(val nodeId: Long, val heardSnr: Float?, val heardRssi: Int?, val snrTowards: Float)

/**
 * The session's last ping and the nodes that have answered it, by node. [refused] when firmware refused it within
 * [FIRMWARE_TRACEROUTE_LIMIT] of another traceroute, so it never went out.
 */
data class SurveyPing(
    val id: Int,
    val sentAtMs: Long,
    val answers: Map<Long, PingAnswer> = emptyMap(),
    val refused: Boolean = false,
) {
    /**
     * With [row]'s answer, when [requestId], the request its packet answers, is this ping: not an earlier ping or a
     * traceroute the app sent itself, whose direct replies carry the same SNR.
     */
    fun withAnswer(row: SurveyMeasurement, requestId: Int): SurveyPing {
        val towards = row.snrTowards?.takeIf { requestId == id } ?: return this
        return copy(
            answers = answers + (row.senderNodeId to PingAnswer(row.senderNodeId, row.rxSnr, row.rxRssi, towards)),
        )
    }
}
