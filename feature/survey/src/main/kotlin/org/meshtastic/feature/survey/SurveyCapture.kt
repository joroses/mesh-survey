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

import org.meshtastic.core.model.util.isLora
import org.meshtastic.core.model.util.rxTimeOrNull
import org.meshtastic.core.model.util.snrOrNull
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MeshPacket.TransportMechanism

/**
 * On connect the node replays packets from its history, hours old and without RSSI. A packet whose radio time trails
 * the phone's clock by more than this is such a replay. Live packets trail by up to about 21 s, because the node's
 * clock is a few seconds off, so a tighter limit would drop them.
 */
const val LATE_PACKET_MS = 60_000L

private const val MILLIS_PER_SECOND = 1_000L
private const val RELAY_BYTE_MASK = 0xFF

/**
 * Turns a packet the survey node heard into a measurement, or null when it is not one: not heard over LoRa, relayed by
 * MQTT, sent by the survey node itself, from node 0, or replayed from the node's history. Encrypted packets stay; their
 * headers carry everything coverage needs.
 */
@Suppress("LongParameterList")
fun measurementOf(
    packet: MeshPacket,
    surveyNode: Int,
    sessionId: String,
    wallTimeMs: Long,
    elapsedRealtimeMs: Long,
    fix: SurveyFix?,
): SurveyMeasurement? {
    val rxTimeMs = packet.rxTimeOrNull()?.toUInt()?.toLong()?.times(MILLIS_PER_SECOND)
    val replayed = rxTimeMs != null && rxTimeMs < wallTimeMs - LATE_PACKET_MS
    val heardOverTheAir =
        packet.heardOverLora() && packet.via_mqtt != true && packet.from != surveyNode && packet.from != 0
    if (!heardOverTheAir || replayed) return null
    val usableFix = fix?.takeIf { it.elapsedRealtimeMs <= elapsedRealtimeMs }
    return SurveyMeasurement(
        sessionId = sessionId,
        heardAtMs = wallTimeMs,
        latitude = usableFix?.latitude,
        longitude = usableFix?.longitude,
        accuracyM = usableFix?.accuracyM,
        fixAgeMs = usableFix?.let { elapsedRealtimeMs - it.elapsedRealtimeMs },
        senderNodeId = packet.from.toUInt().toLong(),
        recipientNodeId = packet.to.toUInt().toLong(),
        packetId = packet.id.toUInt().toLong(),
        rxSnr = packet.snrOrNull(),
        rxRssi = packet.rx_rssi,
        hopStart = packet.hop_start,
        hopLimit = packet.hop_limit,
        // Older firmware sends no relay byte (0). The byte is only the last byte of the relay's node number.
        relayByte = packet.relay_node.takeIf { it != 0 }?.and(RELAY_BYTE_MASK),
    )
}

/**
 * Firmware before 2.7.5 leaves every packet at TRANSPORT_INTERNAL, so [isLora] alone would record nothing on it. There
 * an RSSI marks a packet the radio received; newer firmware gives packets from MQTT, UDP or the API their own
 * transport.
 */
private fun MeshPacket.heardOverLora(): Boolean =
    isLora() || (transport_mechanism == TransportMechanism.TRANSPORT_INTERNAL && (rx_rssi ?: 0) != 0)
