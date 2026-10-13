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

    private fun session(id: String, startedAt: Long) = SurveySession(
        id = id,
        startedAt = startedAt,
        receiverNodeId = 1,
        receiverName = "Survey node",
        modemPreset = "LONG_FAST",
        spreadingFactor = 11,
        bandwidthKhz = 250f,
        frequencyMhz = 869.525f,
        appVersion = "test",
    )

    private fun measurement(sessionId: String, heardAt: Long) = SurveyMeasurement(
        sessionId = sessionId,
        heardAtMs = heardAt,
        latitude = null,
        longitude = null,
        accuracyM = null,
        fixAgeMs = null,
        senderNodeId = 2,
        recipientNodeId = 4_294_967_295,
        packetId = heardAt,
        rxSnr = null,
        rxRssi = null,
        hopStart = 3,
        hopLimit = 3,
        relayByte = null,
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
    fun `deleting a session deletes its measurements`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        dao.insertMeasurement(measurement("s", 1))

        dao.deleteSession("s")

        assertEquals(emptyList(), dao.sessions().first())
        assertEquals(emptyList(), dao.measurementsAfter("s", 0, limit = 10))
    }
}
