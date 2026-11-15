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

import org.meshtastic.core.model.Channel
import org.meshtastic.core.model.util.isLora
import org.meshtastic.core.model.util.rxTimeOrNull
import org.meshtastic.core.model.util.snrOrNull
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MeshPacket.TransportMechanism
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * On connect the node replays packets from its history, hours old and without RSSI. A packet whose radio time trails
 * the phone's clock by more than this is such a replay, as is any packet without an RSSI. Live packets trail by up to
 * about 21 s, because the node's clock is a few seconds off, so a tighter limit would drop them.
 */
const val LATE_PACKET_MS = 60_000L

private const val MILLIS_PER_SECOND = 1_000L
private const val RELAY_BYTE_MASK = 0xFF
private const val OK_TO_MQTT_MASK = 1

/**
 * SigSleuth skips a measurement whose fix is older or less accurate than this (its MAX_FIX_AGE_MS and MAX_ACCURACY_M).
 */
const val SIGSLEUTH_MAX_FIX_AGE_S = 10
const val SIGSLEUTH_MAX_ACCURACY_M = 100

/** SigSleuth's map cells are Web Mercator tiles at this zoom (its FINE_LEVEL), about 190 m across in Poland. */
private const val CELL_LEVEL = 17
private const val MAX_MERCATOR_LATITUDE = 85.05112878
private const val HALF_TURN_DEGREES = 180.0
private const val QUARTER_TURN_DEGREES = 90.0

data class SurveyCell(val x: Int, val y: Int) {
    companion object {
        /** The cell holding a position, as SigSleuth's `coverage.fine_cell` finds it. */
        fun of(latitude: Double, longitude: Double): SurveyCell {
            val scale = 1 shl CELL_LEVEL
            val phi = latitude.coerceIn(-MAX_MERCATOR_LATITUDE, MAX_MERCATOR_LATITUDE) * PI / HALF_TURN_DEGREES
            val x = ((longitude + HALF_TURN_DEGREES) / (2 * HALF_TURN_DEGREES) * scale).toInt()
            val y = ((1 - ln(tan(phi) + 1 / cos(phi)) / PI) / 2 * scale).toInt()
            return SurveyCell(x.coerceIn(0, scale - 1), y.coerceIn(0, scale - 1))
        }
    }
}

/** The cell of a measurement with this fix, or null when SigSleuth would skip it (its `skip_reason`). */
fun sigSleuthCell(latitude: Double, longitude: Double, accuracyM: Float?, fixAgeMs: Long): SurveyCell? {
    val kept =
        !(latitude == 0.0 && longitude == 0.0) &&
            latitude in -QUARTER_TURN_DEGREES..QUARTER_TURN_DEGREES &&
            longitude in -HALF_TURN_DEGREES..HALF_TURN_DEGREES &&
            (accuracyM ?: 0f) <= SIGSLEUTH_MAX_ACCURACY_M &&
            fixAgeMs <= SIGSLEUTH_MAX_FIX_AGE_S * MILLIS_PER_SECOND
    return if (kept) SurveyCell.of(latitude, longitude) else null
}

/**
 * The indices of the survey node's channels whose key is the default one, the only key SigSleuth decrypts. A decoded
 * packet's `channel` is such an index; an encrypted one's is a hash, and it has no bitfield to read anyway.
 */
fun defaultKeyChannels(channels: ChannelSet): Set<Int> =
    channels.settings.indices.filterTo(mutableSetOf()) { Channel(channels.settings[it]).psk == Channel.default.psk }

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
    defaultKeyChannels: Set<Int>,
): SurveyMeasurement? {
    val rxTimeMs = packet.rxTimeOrNull()?.toUInt()?.toLong()?.times(MILLIS_PER_SECOND)
    val replayed = rxTimeMs != null && rxTimeMs < wallTimeMs - LATE_PACKET_MS
    val heardOverTheAir =
        packet.heardOverLora() && packet.via_mqtt != true && packet.from != surveyNode && packet.from != 0
    if (!heardOverTheAir || replayed) return null
    val usableFix = fix?.takeIf { it.elapsedRealtimeMs <= elapsedRealtimeMs }
    val fixAgeMs = usableFix?.let { elapsedRealtimeMs - it.elapsedRealtimeMs }
    val cell = usableFix?.let { sigSleuthCell(it.latitude, it.longitude, it.accuracyM, fixAgeMs ?: 0) }
    return SurveyMeasurement(
        sessionId = sessionId,
        heardAtMs = wallTimeMs,
        latitude = usableFix?.latitude,
        longitude = usableFix?.longitude,
        accuracyM = usableFix?.accuracyM,
        fixAgeMs = fixAgeMs,
        senderNodeId = packet.from.toUInt().toLong(),
        recipientNodeId = packet.to.toUInt().toLong(),
        packetId = packet.id.toUInt().toLong(),
        rxSnr = packet.snrOrNull(),
        rxRssi = packet.rx_rssi,
        hopStart = packet.hop_start,
        hopLimit = packet.hop_limit,
        // Older firmware sends no relay byte (0). The byte is only the last byte of the relay's node number.
        relayByte = packet.relay_node.takeIf { it != 0 }?.and(RELAY_BYTE_MASK),
        // Firmware reads a missing bitfield as not OK (MQTT::onSend).
        okToMqtt = packet.decoded?.let { ((it.bitfield ?: 0) and OK_TO_MQTT_MASK) != 0 },
        // A direct message the survey node decrypted with its private key still carries a channel index.
        defaultKey = packet.decoded?.let { packet.pki_encrypted != true && packet.channel in defaultKeyChannels },
        cellX = cell?.x,
        cellY = cell?.y,
    )
}

/**
 * Only a packet the radio received carries an RSSI. The packets a node replays from its history on connect are still
 * marked LoRa but carry none, so this drops them even when their radio time is recent or missing. Firmware before 2.7.5
 * leaves every packet at TRANSPORT_INTERNAL, so [isLora] alone would record nothing on it; newer firmware gives packets
 * from MQTT, UDP or the API their own transport.
 */
private fun MeshPacket.heardOverLora(): Boolean =
    (rx_rssi ?: 0) != 0 && (isLora() || transport_mechanism == TransportMechanism.TRANSPORT_INTERNAL)
