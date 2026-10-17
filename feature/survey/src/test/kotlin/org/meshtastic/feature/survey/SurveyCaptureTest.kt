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

import okio.ByteString.Companion.decodeBase64
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Data
import org.meshtastic.proto.MeshPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurveyCaptureTest {
    private val now = 1_789_633_458_987L
    private val elapsed = 50_000L
    private val fix = SurveyFix(elapsedRealtimeMs = elapsed - 700, latitude = 41.9, longitude = 1.57, accuracyM = 14f)

    private fun packet(change: MeshPacket.Builder.() -> Unit = {}) = MeshPacket.Builder()
        .apply {
            from = -1_234 // A node number above 2^31 arrives negative.
            to = -1
            id = -566_270_708
            rx_time = (now / 1_000).toInt()
            rx_snr = 12.75f
            rx_rssi = -82
            hop_start = 4
            hop_limit = 2
            relay_node = 0x11A
            transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            change()
        }
        .build()

    private fun capture(packet: MeshPacket, withFix: SurveyFix? = fix) = measurementOf(
        packet,
        surveyNode = 99,
        sessionId = "s",
        wallTimeMs = now,
        elapsedRealtimeMs = elapsed,
        withFix,
        defaultKeyChannels = setOf(0),
    )

    private fun decoded(bitfield: Int?) = Data.Builder().apply { this.bitfield = bitfield }.build()

    @Test
    fun `a decoded packet keeps its OK to MQTT bit and whether its channel uses the default key`() {
        val ok = assertNotNull(capture(packet { decoded = decoded(bitfield = 0b11) }))
        assertEquals(true, ok.okToMqtt)
        assertEquals(true, ok.defaultKey)

        val optedOut =
            assertNotNull(
                capture(
                    packet {
                        decoded = decoded(bitfield = 0b10)
                        channel = 1
                    },
                ),
            )
        assertEquals(false, optedOut.okToMqtt)
        assertEquals(false, optedOut.defaultKey)

        val directMessage = packet {
            decoded = decoded(bitfield = 1)
            pki_encrypted = true
        }
        assertEquals(false, assertNotNull(capture(directMessage)).defaultKey)

        // Firmware before the bitfield sends none; it reads that as not OK.
        assertEquals(false, assertNotNull(capture(packet { decoded = decoded(bitfield = null) })).okToMqtt)
    }

    @Test
    fun `an encrypted packet leaves both consent fields unknown`() {
        val row = assertNotNull(capture(packet { channel = 0 }))
        assertNull(row.okToMqtt)
        assertNull(row.defaultKey)
    }

    @Test
    fun `only the default key counts as default, whether short or expanded`() {
        fun settings(psk: String) = ChannelSettings.Builder().apply { this.psk = psk.decodeBase64()!! }.build()
        val channels =
            ChannelSet.Builder()
                .apply {
                    settings =
                        listOf(
                            settings("AQ=="), // The short code for the default key.
                            settings("1PG7OiApB1nwvP+rz05pAQ=="), // The same key written out.
                            settings("Ag=="), // Another short key.
                            settings("1fG7OiApB1nwvP+rz05pAQ=="), // A private key, one bit from the default.
                            settings(""), // No encryption.
                        )
                }
                .build()

        val found = defaultKeyChannels(channels)

        assertTrue(0 in found && 1 in found)
        assertFalse(2 in found || 3 in found || 4 in found)
    }

    @Test
    fun `a heard packet becomes a measurement with unsigned ids, the fix and its age`() {
        val row = assertNotNull(capture(packet()))

        assertEquals(now, row.heardAtMs)
        assertEquals(41.9, row.latitude)
        assertEquals(700L, row.fixAgeMs)
        assertEquals(4_294_966_062L, row.senderNodeId)
        assertEquals(4_294_967_295L, row.recipientNodeId)
        assertEquals(3_728_696_588L, row.packetId)
        assertEquals(12.75f, row.rxSnr)
        assertEquals(-82, row.rxRssi)
        assertEquals(0x1A, row.relayByte)
    }

    @Test
    fun `packets that are not heard over the air by another node are skipped`() {
        assertNull(capture(packet { via_mqtt = true }))
        assertNull(capture(packet { transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_MQTT }))
        assertNull(capture(packet { from = 99 }))
        assertNull(capture(packet { from = 0 }))
    }

    @Test
    fun `firmware before 2_7_5 marks every packet internal, so an RSSI marks one heard over the air`() {
        val internal = MeshPacket.TransportMechanism.TRANSPORT_INTERNAL
        assertNotNull(capture(packet { transport_mechanism = internal }))
        assertNull(
            capture(
                packet {
                    transport_mechanism = internal
                    rx_rssi = null
                },
            ),
        )
        assertNull(
            capture(
                packet {
                    transport_mechanism = internal
                    via_mqtt = true
                },
            ),
        )
    }

    @Test
    fun `a packet replayed from the node's history is skipped, a slightly late one is kept`() {
        assertNull(capture(packet { rx_time = ((now - LATE_PACKET_MS) / 1_000 - 1).toInt() }))
        assertNotNull(capture(packet { rx_time = ((now - 21_000) / 1_000).toInt() }))
        assertNotNull(capture(packet { rx_time = null }))
    }

    @Test
    fun `missing readings stay null and a zero SNR stays a reading`() {
        val row =
            assertNotNull(
                capture(
                    packet {
                        rx_snr = 0f
                        rx_rssi = null
                        relay_node = 0
                    },
                ),
            )

        assertEquals(0f, row.rxSnr)
        assertNull(row.rxRssi)
        assertNull(row.relayByte)
    }

    @Test
    fun `an encrypted packet is kept, and a packet without a fix is kept without a position`() {
        assertNotNull(capture(packet { decoded = null }))
        val row = assertNotNull(capture(packet(), withFix = null))

        assertNull(row.latitude)
        assertNull(row.fixAgeMs)
    }
}
