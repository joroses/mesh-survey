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

import org.meshtastic.core.model.Node
import org.meshtastic.proto.Config
import org.meshtastic.proto.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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

    @Suppress("LongParameterList")
    private fun DirectNeighbours.guess(
        relayByte: Int,
        atMs: Long,
        senderId: Long,
        latitude: Double? = null,
        longitude: Double? = null,
        roleOf: (Long) -> Config.DeviceConfig.Role? = { null },
        reported: List<RelayCandidate.Reported> = emptyList(),
    ) = weigh(relayByte, atMs, senderId, latitude, longitude, roleOf, reported).guess

    private fun heard(vararg ids: Long) = ids.map { RelayCandidate.Heard(it) }

    private fun reported(id: Long, latitude: Double) = RelayCandidate.Reported(id, latitude, 0.0)

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
    fun `without a packet fix one recent neighbour is likely`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(30))))

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `without a packet fix older evidence is possible and expired evidence unknown`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(90))))

        assertEquals(RelayGuess.Possible(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
        val tooOld = now - DIRECT_HISTORY.inWholeMilliseconds - 1
        assertEquals(RelayGuess.Unknown, DirectNeighbours(listOf(DirectSender(0x13F, tooOld))).guess(0x3F, now, 0x500))
    }

    @Test
    fun `without a packet fix a recent neighbour beats an older one`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(90)), DirectSender(0x23F, ago(10))))

        assertEquals(RelayGuess.Likely(0x23F), neighbours.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `without a packet fix the best time tier is ambiguous newest first`() {
        val recent = DirectNeighbours(listOf(DirectSender(0x13F, ago(50)), DirectSender(0x23F, ago(10))))
        val older = DirectNeighbours(listOf(DirectSender(0x13F, ago(500)), DirectSender(0x23F, ago(100))))

        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), recent.guess(0x3F, now, senderId = 0x500))
        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), older.guess(0x3F, now, senderId = 0x500))
    }

    private fun DirectNeighbours.guessAt(latitude: Double, at: Long = now) =
        guess(0x3F, at, senderId = 0x500, latitude = latitude, longitude = 0.0)

    @Test
    fun `same-byte neighbours heard from one cell stay ambiguous past either one's hour`() {
        val neighbours = DirectNeighbours()
        // Reverse insertion and last-heard order must not affect the node-number tie break.
        neighbours.record(row(0x23F, 0x3F, at = ago(10), latitude = 1.0002))
        neighbours.record(row(0x13F, 0x3F, at = ago(50), latitude = 1.0))
        val expected = RelayGuess.Ambiguous(listOf(0x13F, 0x23F))

        assertEquals(expected, neighbours.guessAt(1.0))
        assertEquals(expected, neighbours.guessAt(1.0, now + 11.minutes.inWholeMilliseconds))
        assertEquals(expected, neighbours.guessAt(1.0, now + 51.minutes.inWholeMilliseconds))
        neighbours.record(row(0x13F, 0x3F, at = now, latitude = 1.0001))
        assertEquals(expected, neighbours.guessAt(1.0, now + 2.hours.inWholeMilliseconds))
    }

    @Test
    fun `moving from A's reception zone to B's makes B likely once heard there`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 0.0))
        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
        assertEquals(RelayGuess.Possible(0x13F), neighbours.guessAt(0.1))

        neighbours.record(row(0x23F, 0x3F, latitude = 0.1))

        assertEquals(RelayGuess.Likely(0x23F), neighbours.guessAt(0.1))
        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
    }

    @Test
    fun `a router heard along a route ranks by its closest reception rather than its latest`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x23F, 0x3F, at = ago(500), latitude = 0.1))
        neighbours.record(row(0x23F, 0x3F, at = ago(100), latitude = 0.5))
        neighbours.record(row(0x13F, 0x3F, at = ago(1), latitude = 0.25)) // About 15 km away.

        assertEquals(RelayGuess.Likely(0x23F), neighbours.guessAt(0.1))
        // None near: the router's earlier reception still ranks before the chatty distant node.
        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), neighbours.guessAt(0.12))
    }

    @Test
    fun `yesterday's cells count immediately on return even after a newer reception elsewhere`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, at = now - 1.days.inWholeMilliseconds, latitude = 0.0))
        neighbours.record(row(0x13F, 0x3F, latitude = 0.5))
        neighbours.record(row(0x23F, 0x3F, latitude = 0.5))

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
    }

    @Test
    fun `cells expire after 30 days even when the node was recently heard elsewhere`() {
        val cutoff = now - DIRECT_HISTORY.inWholeMilliseconds
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, at = cutoff, latitude = 0.0))
        neighbours.record(row(0x13F, 0x3F, latitude = 0.2))

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
        assertEquals(RelayGuess.Possible(0x13F), neighbours.guessAt(0.0, now + 1))
        val expired = DirectNeighbours(listOf(DirectSender(0x23F, cutoff - 1, mapOf(DirectCell(0, 0) to cutoff - 1))))
        assertEquals(RelayGuess.Unknown, expired.guessAt(0.0))
    }

    @Test
    fun `a candidate without cells is near only within the hour`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(60))))
        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
        assertEquals(RelayGuess.Possible(0x13F), neighbours.guessAt(0.0, now + 1))

        neighbours.record(row(0x23F, 0x3F, at = ago(500), latitude = 0.0))
        assertEquals(RelayGuess.Ambiguous(listOf(0x23F, 0x13F)), neighbours.guessAt(0.0))
        assertEquals(RelayGuess.Likely(0x23F), neighbours.guessAt(0.0, now + 1))
    }

    @Test
    fun `with none near candidates sort by distance then node number and fixless last`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, now)))
        neighbours.record(row(0x33F, 0x3F, at = ago(500), latitude = 0.1))
        neighbours.record(row(0x23F, 0x3F, at = ago(100), latitude = 0.1))
        neighbours.record(row(0x43F, 0x3F, at = ago(5), latitude = 0.2))

        assertEquals(
            RelayGuess.Ambiguous(listOf(0x23F, 0x33F, 0x43F, 0x13F)),
            neighbours.guessAt(0.0, now + 2.hours.inWholeMilliseconds),
        )
    }

    @Test
    fun `near means within one kilometre of the cell centre`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, at = ago(500), latitude = 0.0))

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.008))
        assertEquals(RelayGuess.Possible(0x13F), neighbours.guessAt(0.010))
    }

    @Test
    fun `fixless packets use time tiers even when reception cells exist`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, at = ago(90), latitude = 0.0))
        neighbours.record(row(0x23F, 0x3F, at = ago(10), latitude = 0.5))

        assertEquals(RelayGuess.Likely(0x23F), neighbours.guess(0x3F, now, senderId = 0x500))
        assertEquals(RelayGuess.Likely(0x13F), neighbours.guessAt(0.0))
    }

    @Test
    fun `CLIENT_MUTE is excluded from both guess tiers and an unknown role keeps its candidate`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 0.0))
        neighbours.record(row(0x23F, 0x3F, latitude = 0.1))
        val roles = mutableMapOf(0x13FL to Config.DeviceConfig.Role.CLIENT_MUTE)
        val roleOf = { id: Long -> roles[id] }

        assertEquals(RelayGuess.Possible(0x23F), neighbours.guess(0x3F, now, 0x500, 0.0, 0.0, roleOf))
        assertEquals(RelayGuess.Likely(0x23F), neighbours.guess(0x3F, now, 0x500, roleOf = roleOf))
        roles[0x13F] = Config.DeviceConfig.Role.ROUTER
        assertEquals(RelayGuess.Likely(0x13F), neighbours.guess(0x3F, now, 0x500, 0.0, 0.0, roleOf))
    }

    @Test
    fun `a mute node alone cannot be a relay even if recent and nearby`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 0.0))
        val mute = { _: Long -> Config.DeviceConfig.Role.CLIENT_MUTE }

        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, 0x500, 0.0, 0.0, mute))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, 0x500, roleOf = mute))
    }

    @Test
    fun `relay byte FF matches neighbours ending in 00 and in ff`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x100, 0xFF, at = ago(5)))
        neighbours.record(row(0x2FF, 0xFF, at = ago(1)))

        assertEquals(RelayGuess.Ambiguous(listOf(0x2FF, 0x100)), neighbours.guess(0xFF, now, senderId = 0x500))
        assertEquals(
            RelayGuess.Ambiguous(listOf(0x100, 0x2FF)),
            neighbours.guess(0xFF, now, senderId = 0x500, latitude = 0.0, longitude = 0.0),
        )
    }

    @Test
    fun `only packets heard directly are recorded, and a sender never relays its own packet`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, hopLimit = 2)) // Relayed by a node with the sender's byte.
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, senderId = 0x500))

        neighbours.record(row(0x13F, 0x3F, at = ago(1)))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, senderId = 0x13F))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, 0x13F, latitude = 0.0, longitude = 0.0))
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x40, now, senderId = 0x500))
    }

    @Test
    fun `a neighbour merges cells by latest direct time without mutating snapshots`() {
        val first = DirectCell.of(1.0, 0.0)
        val second = DirectCell.of(2.0, 0.0)
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(10), mapOf(first to ago(20)))))
        val snapshot = neighbours.all
        neighbours.record(row(0x13F, 0x3F, at = ago(5))) // Newer, but without a fix.
        neighbours.record(row(0x13F, 0x3F, at = ago(30), latitude = 2.0))
        neighbours.record(row(0x13F, 0x3F, at = ago(25), latitude = 1.0001)) // Same cell, older evidence.

        assertEquals(DirectSender(0x13F, ago(5), mapOf(first to ago(20), second to ago(30))), neighbours.all[0x13F])
        assertEquals(DirectSender(0x13F, ago(10), mapOf(first to ago(20))), snapshot[0x13F])

        neighbours.record(row(0x13F, 0x3F, at = ago(1), latitude = 1.0002))
        assertEquals(DirectSender(0x13F, ago(1), mapOf(first to ago(1), second to ago(30))), neighbours.all[0x13F])
    }

    @Test
    fun `nearest uses cell centres in the history and returns the reception time`() {
        val heardAt =
            DirectSender(0x13F, now, mapOf(DirectCell.of(0.0, 0.0) to now, DirectCell.of(1.0, 0.0) to ago(20)))

        assertEquals(NearestDirect(0.0, ago(20)), heardAt.nearest(1.0, 0.0, ago(30)))
        assertEquals(111_195.0, heardAt.nearest(1.0, 0.0, ago(10))!!.meters, 1.0) // One degree of latitude.
        assertNull(DirectSender(0x13F, now).nearest(1.0, 0.0, ago(30)))
    }

    @Test
    fun `live cells round half coordinates away from zero like SQLite`() {
        assertEquals(DirectCell(1, -1), DirectCell.of(0.001, -0.001))
        assertEquals(DirectCell(0, 0), DirectCell.of(0.0009, -0.0009))
    }

    @Test
    fun `a neighbour keeps its latest direct time`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(10))))
        neighbours.record(row(0x13F, 0x3F, at = ago(500))) // An older row must not push it back.

        assertEquals(RelayGuess.Likely(0x13F), neighbours.guess(0x3F, now, senderId = 0x500))
    }

    @Test
    fun `every candidate is listed, the guessed tier first and the rest by distance`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 0.0))
        neighbours.record(row(0x23F, 0x3F, latitude = 0.1)) // About 11 km away.
        val positions = listOf(reported(0x33F, 0.02), reported(0x43F, 0.5)) // About 2 km and 55 km.

        val evidence = neighbours.weigh(0x3F, now, 0x500, 0.0, 0.0, reported = positions)

        assertEquals(RelayGuess.Likely(0x13F), evidence.guess)
        assertEquals(heard(0x13F) + reported(0x33F, 0.02) + heard(0x23F), evidence.candidates)
    }

    @Test
    fun `a reported position joins the possible tier but never makes a guess likely`() {
        val neighbours = DirectNeighbours()
        val alone = neighbours.weigh(0x3F, now, 0x500, 0.0, 0.0, reported = listOf(reported(0x33F, 0.0)))
        assertEquals(RelayGuess.Possible(0x33F), alone.guess)

        neighbours.record(row(0x13F, 0x3F, latitude = 0.1))
        assertEquals(
            RelayGuess.Ambiguous(listOf(0x33F, 0x13F)),
            neighbours.guess(0x3F, now, 0x500, 0.0, 0.0, reported = listOf(reported(0x33F, 0.0))),
        )
    }

    @Test
    fun `candidates farther than 30 km are dropped, unless they report a position nearby`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 2.0)) // About 220 km away, on a road trip.
        assertEquals(RelayGuess.Unknown, neighbours.guessAt(0.0))
        val farReport = listOf(reported(0x23F, 0.3)) // About 33 km away.
        assertEquals(RelayGuess.Unknown, neighbours.guess(0x3F, now, 0x500, 0.0, 0.0, reported = farReport))

        val evidence = neighbours.weigh(0x3F, now, 0x500, 0.0, 0.0, reported = listOf(reported(0x13F, 0.02)))
        assertEquals(RelayGuess.Possible(0x13F), evidence.guess)
        assertEquals(listOf(reported(0x13F, 0.02)), evidence.candidates)
    }

    @Test
    fun `a node heard directly within reach is listed once, by its receptions`() {
        val neighbours = DirectNeighbours()
        neighbours.record(row(0x13F, 0x3F, latitude = 0.1))

        val evidence = neighbours.weigh(0x3F, now, 0x500, 0.0, 0.0, reported = listOf(reported(0x13F, 0.0)))

        assertEquals(heard(0x13F), evidence.candidates)
    }

    @Test
    fun `reported positions skip the sender and mute nodes, and count only with a packet fix`() {
        val neighbours = DirectNeighbours(listOf(DirectSender(0x13F, ago(90)), DirectSender(0x23F, ago(10))))
        val positions = listOf(reported(0x33F, 0.0), reported(0x43F, 0.0), reported(0x500, 0.0))
        val mute = { id: Long -> Config.DeviceConfig.Role.CLIENT_MUTE.takeIf { id == 0x43FL } }

        // Neither has cells: the recent one is near, the other keeps no distance and goes last.
        val located = neighbours.weigh(0x3F, now, 0x500, 0.0, 0.0, mute, positions)
        assertEquals(RelayGuess.Likely(0x23F), located.guess)
        assertEquals(heard(0x23F) + reported(0x33F, 0.0) + heard(0x13F), located.candidates)

        val fixless = neighbours.weigh(0x3F, now, 0x500, roleOf = mute, reported = positions)
        assertEquals(RelayGuess.Likely(0x23F), fixless.guess)
        assertEquals(heard(0x23F, 0x13F), fixless.candidates)
    }

    @Test
    fun `reported nodes need the byte, a position and a recent hearing, and never the survey radio`() {
        val since = ago(60)
        fun node(num: Int, lastHeardMs: Long = now, latitude: Double = 0.5) = Node(
            num = num,
            lastHeard = (lastHeardMs / 1_000).toInt(),
            position = position((latitude * 1e7).toInt(), 10_000_000),
        )
        val nodes =
            listOf(
                node(0x13F),
                node(0x8000_023F.toInt()),
                node(0x33F, lastHeardMs = since - 1_000),
                node(0x43F, latitude = 0.0), // No position.
                node(0x53F), // The survey radio.
                node(0x140),
            )

        val found = reportedWith(0x3F, nodes, since, surveyNode = 0x53F)

        assertEquals(listOf(0x13FL, 0x8000_023FL), found.map { it.nodeId })
        assertEquals(0.5, found.first().latitude, 1e-6)
        assertEquals(1.0, found.first().longitude, 1e-6)
    }
}

private fun position(latitudeI: Int, longitudeI: Int) = Position.Builder()
    .apply {
        latitude_i = latitudeI
        longitude_i = longitudeI
    }
    .build()
