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

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SurveyDaoTest {
    private val database =
        Room.inMemoryDatabaseBuilder<SurveyDatabase>(factory = { SurveyDatabase_Impl() })
            .setDriver(BundledSQLiteDriver())
            .build()
    private val dao = database.surveyDao()

    @AfterTest fun close() = database.close()

    private fun session(id: String, startedAt: Long, receiverNodeId: Long = 1) = SurveySession(
        id = id,
        startedAt = startedAt,
        receiverNodeId = receiverNodeId,
        receiverName = "Survey node",
        modemPreset = "LONG_FAST",
        spreadingFactor = 11,
        bandwidthKhz = 250f,
        frequencyMhz = 869.525f,
        appVersion = "test",
    )

    @Suppress("LongParameterList")
    private fun measurement(
        sessionId: String,
        heardAt: Long,
        sender: Long = 2,
        hopStart: Int = 3,
        hopLimit: Int = 3,
        relayByte: Int? = null,
    ) = SurveyMeasurement(
        sessionId = sessionId,
        heardAtMs = heardAt,
        latitude = null,
        longitude = null,
        accuracyM = null,
        fixAgeMs = null,
        senderNodeId = sender,
        recipientNodeId = 4_294_967_295,
        packetId = heardAt,
        rxSnr = null,
        rxRssi = null,
        hopStart = hopStart,
        hopLimit = hopLimit,
        relayByte = relayByte,
    )

    @Test
    fun `measurements page after the uploaded seq, oldest session first`() = runTest {
        dao.insertSession(session("old", startedAt = 1))
        dao.insertSession(session("new", startedAt = 2))
        val oldSeqs = (1..5L).map { dao.insertMeasurement(measurement("old", it)) }
        dao.insertMeasurement(measurement("new", 10))

        dao.uploaded("old", oldSeqs[2], at = 100, kept = 3, skipped = 0, skipReasons = null)

        val pending = dao.sessionsToUpload()
        assertEquals(listOf("old", "new"), pending.map { it.session.id })
        assertEquals(oldSeqs[2], pending.first().session.uploadedSeq)
        assertEquals(oldSeqs.drop(3), dao.measurementsAfter("old", oldSeqs[2], limit = 500).map { it.seq })
        assertEquals(oldSeqs.subList(3, 4), dao.measurementsAfter("old", oldSeqs[2], limit = 1).map { it.seq })
    }

    @Test
    fun `a fully uploaded session is not pending, and an older ack never moves it back`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        val last = (1..3L).map { dao.insertMeasurement(measurement("s", it)) }.last()

        dao.uploaded("s", last, at = 100, kept = 1, skipped = 2, skipReasons = """{"no_position":2}""")
        dao.uploaded("s", 1, at = 200, kept = 1, skipped = 2, skipReasons = """{"no_position":2}""")

        assertEquals(emptyList(), dao.sessionsToUpload())
        val session = dao.sessions().first().single().session
        assertEquals(last, session.uploadedSeq)
        assertEquals(1L, session.serverKept)
        assertEquals(2L, session.serverSkipped)
        assertEquals(mapOf("no_position" to 2L), SurveyUploader.decodeSkipReasons(session.serverSkipReasons))
    }

    @Test
    fun `a session a dead process left recording ends at its last measurement`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        dao.insertMeasurement(measurement("s", 42))

        dao.interruptRecording()

        val summary = dao.sessions().first().single()
        assertEquals(SurveyStatus.INTERRUPTED, summary.session.status)
        assertEquals(42L, summary.session.endedAt)
        assertEquals(1, summary.measurementCount)
    }

    @Test
    fun `direct senders are this radio's, heard directly since the cutoff, at their latest time`() = runTest {
        dao.insertSession(session("old", startedAt = 1))
        dao.insertSession(session("new", startedAt = 50))
        dao.insertSession(session("other radio", startedAt = 1, receiverNodeId = 9))
        dao.insertMeasurement(measurement("old", 20, sender = 0x102, relayByte = 0x02))
        dao.insertMeasurement(measurement("new", 60, sender = 0x102, relayByte = 0x02)) // The latest time wins.
        dao.insertMeasurement(measurement("new", 55, sender = 0x102, relayByte = 0x02))
        dao.insertMeasurement(measurement("new", 61, sender = 0x200, relayByte = 0xFF)) // 00 is sent as FF.
        dao.insertMeasurement(measurement("new", 62, sender = 0x103, hopLimit = 2, relayByte = 0x03)) // A hop used.
        dao.insertMeasurement(measurement("new", 63, sender = 0x104, relayByte = 0x3F)) // Someone else's byte.
        dao.insertMeasurement(measurement("new", 64, sender = 0x105)) // No relay byte.
        dao.insertMeasurement(measurement("old", 5, sender = 0x106, relayByte = 0x06)) // Before the cutoff.
        dao.insertMeasurement(measurement("other radio", 70, sender = 0x107, relayByte = 0x07))

        val senders = dao.directSenders(receiverNodeId = 1, sinceMs = 10)

        assertEquals(listOf(DirectSender(0x102, 60), DirectSender(0x200, 61)), senders.sortedBy { it.nodeId })
    }

    @Test
    fun `deleting a session deletes its measurements`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        dao.insertMeasurement(measurement("s", 1))

        dao.deleteSession("s")

        assertEquals(emptyList(), dao.sessions().first())
        assertEquals(emptyList(), dao.measurementsAfter("s", 0, limit = 10))
    }
}
