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
package org.meshtastic.feature.survey.ui

import org.meshtastic.feature.survey.NearEvidence
import org.meshtastic.feature.survey.RelayCandidate
import org.meshtastic.feature.survey.RelayGuess
import org.meshtastic.feature.survey.RelayStats
import org.meshtastic.feature.survey.RuledOut
import org.meshtastic.feature.survey.ServerAnswer
import org.meshtastic.feature.survey.ServerCandidate
import org.meshtastic.feature.survey.SurveyMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals

class RelayNodeLinksTest {
    private val packet =
        SurveyMeasurement(
            seq = 1,
            sessionId = "s",
            heardAtMs = 1_000L,
            latitude = null,
            longitude = null,
            accuracyM = null,
            fixAgeMs = null,
            senderNodeId = 0x1001L,
            recipientNodeId = 0xFFFF_FFFFL,
            packetId = 1,
            rxSnr = 5f,
            rxRssi = -90,
            hopStart = 3,
            hopLimit = 2,
            relayByte = 0x38,
        )

    @Test
    fun `mentions confirmed, guessed, server, candidate and ruled-out nodes once in order`() {
        val relay =
            RelayStats(
                packets = 4,
                last = packet,
                guess = RelayGuess.Ambiguous(listOf(0x0000_0238L, 0x0000_0338L)),
                candidates =
                listOf(
                    RelayCandidate.Heard(0x0000_0238L),
                    RelayCandidate.Reported(0x0000_0338L, 41.0, 2.0),
                    RelayCandidate.Mapped(0x0000_0438L, 41.1, 2.1, reachMeters = null, estimated = false),
                ),
                ruledOut = listOf(RuledOut(0x0000_0538L, strongestHere = -120.0, packetSignal = -85.0)),
                server =
                ServerAnswer(
                    seq = 1,
                    heardAtMs = 1_000L,
                    relayByte = 0x38,
                    nodeId = 0x0000_0138L,
                    how = "near_link",
                    shortName = "SRV1",
                    near = NearEvidence(nodeId = 0x0000_0900L, name = "NEAR", km = 1.2),
                ),
            )
        val names = mapOf(0x0000_0238L to "LOC1", 0x0000_0538L to "WEAK")

        assertEquals(
            listOf(
                0x0000_0138L to "SRV1",
                0x0000_0238L to "LOC1",
                0x0000_0338L to "!00000338",
                0x0000_0900L to "NEAR",
                0x0000_0438L to "!00000438",
                0x0000_0538L to "WEAK",
            ),
            relayMentionedNodes(relay, names),
        )
    }

    @Test
    fun `mentions direct sender and unknown server candidates`() {
        val direct = RelayStats(packets = 1, last = packet, guess = RelayGuess.Direct(0x02eb82f4L))
        assertEquals(listOf(0x02eb82f4L to "MQTT"), relayMentionedNodes(direct, mapOf(0x02eb82f4L to "MQTT")))

        val unknown =
            RelayStats(
                packets = 1,
                last = packet,
                guess = RelayGuess.Unknown,
                server =
                ServerAnswer(
                    seq = 1,
                    heardAtMs = 1_000L,
                    relayByte = 0x38,
                    nodeId = null,
                    how = "unknown",
                    shortName = null,
                    candidates = listOf(ServerCandidate(0x0000_0738L, "MAYB", 4.5)),
                ),
            )
        assertEquals(listOf(0x0000_0738L to "MAYB"), relayMentionedNodes(unknown, emptyMap()))
    }
}
