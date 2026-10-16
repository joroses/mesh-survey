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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SurveyRelaysTest {
    private val now = 100 * DIRECT_HISTORY.inWholeMilliseconds

    @Suppress("LongParameterList")
    private fun row(
        sender: Long,
        relayByte: Int?,
        hopStart: Int = 3,
        hopLimit: Int = 3,
        at: Long = now,
        latitude: Double? = null,
    ) = SurveyMeasurement(
        sessionId = "s",
        heardAtMs = at,
        latitude = latitude,
        longitude = latitude?.let { 0.0 },
        accuracyM = null,
        fixAgeMs = null,
        senderNodeId = sender,
        recipientNodeId = 0xFFFFFFFF,
        packetId = 1,
        rxSnr = null,
        rxRssi = null,
        hopStart = hopStart,
        hopLimit = hopLimit,
        relayByte = relayByte,
    )

    private fun ago(minutes: Int) = now - minutes.minutes.inWholeMilliseconds

    @Test
    fun `a node ending in 00 relays as FF`() {
        assertEquals(0x3F, relayByteOf(0x1234_563F))
        assertEquals(0xFF, relayByteOf(0x1234_56FF))
        assertEquals(0xFF, relayByteOf(0x1234_5600))
        assertEquals(0xFF, relayByteOf(0xFFFF_FF00))
    }

    @Test
    fun `direct means no hop used and the sender's own byte`() {
        assertTrue(row(0x13F, 0x3F).isDirect())
        assertTrue(row(0x100, 0xFF).isDirect())
        assertFalse(row(0x13F, 0x3F, hopLimit = 2).isDirect())
        assertFalse(row(0x13F, null).isDirect())
        assertFalse(row(0x13F, 0x40).isDirect())
        assertFalse(row(0x100, 0x00).isDirect())
    }

    @Test
    fun `one neighbour heard directly in the last hour is the likely relay`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(30))))

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `one heard directly only earlier is a possible relay, and one past the history none`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(90))))

        assertEquals(RelayGuess.Possible(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
        val tooOld = now - DIRECT_HISTORY.inWholeMilliseconds - 1
        assertEquals(RelayGuess.Unknown, DirectNeighbours(listOf(DirectSender(0x13F, tooOld))).guess(0x3F, now, 0x500))
    }

    @Test
    fun `a recent neighbour beats an older one with the same byte`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(90)), DirectSender(0x23F, ago(10))))

        assertEquals(RelayGuess.Likely(0x23F), neighbours.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `several neighbours in the best tier are ambiguous, newest first`() {
        val recent = DirectNeighbours(listOf(DirectSender(0x13F, ago(50)), DirectSender(0x23F, ago(10))))
        val older = DirectNeighbours(listOf(DirectSender(0x13F, ago(500)), DirectSender(0x23F, ago(100))))

        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), recent.guess(0x3F, now, senderId = 0x500))
        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), older.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `relay byte FF matches neighbours ending in 00 and in ff`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x100, 0xFF, at = ago(5)))
        neighbours.record(row(0x2FF, 0xFF, at = ago(1)))

        assertEquals(RelayGuess.Ambiguous(listOf(0x2FF, 0x100)), neighbours.guess(0xFF, now, senderId = 0x500))
    }

    @Test
    fun `only packets heard directly are recorded, and a sender never relays its own packet`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, hopLimit = 2)) // Relayed by a node with the sender's byte.
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, senderId = 0x500))

        neighbours.record(row(0x13F, 0x3F, at = ago(1)))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, senderId = 0x13F))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x40, now, senderId = 0x500))
    }

    @Test
    fun `a neighbour keeps its latest direct time and its latest place`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(10), 1.0, 0.0, ago(20))))
        neighbours.record(row(0x13F, 0x3F, at = ago(5))) // Newer, but without a fix.
        neighbours.record(row(0x13F, 0x3F, at = ago(30), latitude = 2.0)) // A fix, but older than the known one.

        assertEquals(DirectSender(0x13F, ago(5), 1.0, 0.0, ago(20)), neighbours.all[0x13F])

        neighbours.record(row(0x13F, 0x3F, at = ago(1), latitude = 3.0))
        assertEquals(DirectSender(0x13F, ago(1), 3.0, 0.0, ago(1)), neighbours.all[0x13F])
    }

    @Test
    fun `distance runs from where the node was heard directly`() {
        val heardAt = DirectSender(0x13F, now, latitude = 0.0, longitude = 0.0, positionMs = now)

        assertEquals(111_195.0, heardAt.metersFrom(1.0, 0.0)!!, 1.0) // One degree of latitude.
        assertNull(DirectSender(0x13F, now).metersFrom(1.0, 0.0))
    }

    @Test
    fun `a neighbour keeps its latest direct time`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(10))))
        neighbours.record(row(0x13F, 0x3F, at = ago(500))) // An older row must not push it back.

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
    }
}
