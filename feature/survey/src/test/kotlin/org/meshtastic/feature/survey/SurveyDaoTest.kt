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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurveyDaoTest {
    private val database =
        Room.inMemoryDatabaseBuilder<SurveyDatabase>(factory = { SurveyDatabase_Impl() })
            .setDriver(BundledSQLiteDriver())
            .build()
    private val dao = database.surveyDao()

    @AfterTest fun close() = database.close()

    @Suppress("LongParameterList")
    private fun session(
        id: String,
        startedAt: Long,
        receiverNodeId: Long = 1,
        spreadingFactor: Int = 11,
        bandwidthKhz: Float = 250f,
        frequencyMhz: Float? = 869.525f,
        upload: Boolean = true,
    ) = SurveySession(
        id = id,
        startedAt = startedAt,
        receiverNodeId = receiverNodeId,
        receiverName = "Survey node",
        modemPreset = "LONG_FAST",
        spreadingFactor = spreadingFactor,
        bandwidthKhz = bandwidthKhz,
        frequencyMhz = frequencyMhz,
        appVersion = "test",
        upload = upload,
    )

    @Suppress("LongParameterList")
    private fun measurement(
        sessionId: String,
        heardAt: Long,
        sender: Long = 2,
        hopStart: Int = 3,
        hopLimit: Int = 3,
        relayByte: Int? = null,
        latitude: Double? = null,
    ) = SurveyMeasurement(
        sessionId = sessionId,
        heardAtMs = heardAt,
        latitude = latitude,
        longitude = latitude?.let { 2.0 },
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
    fun `a private session is never pending until it is allowed`() = runTest {
        dao.insertSession(session("private", startedAt = 1, upload = false))
        dao.insertSession(session("shared", startedAt = 2))
        dao.insertMeasurement(measurement("private", 1))
        dao.insertMeasurement(measurement("shared", 2))

        assertEquals(listOf("shared"), dao.sessionsToUpload().map { it.session.id })
        assertFalse(dao.uploadAllowed("private"))
        assertFalse(dao.uploadAllowed("missing"))

        dao.setUpload("private", true)
        assertTrue(dao.uploadAllowed("private"))
        assertEquals(listOf("private", "shared"), dao.sessionsToUpload().map { it.session.id })

        // A new server resets the progress, not the user's choice.
        dao.resetUploads()
        assertTrue(dao.uploadAllowed("private"))
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
    fun `resetting uploads makes every session pending again with no counts`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        val last = (1..3L).map { dao.insertMeasurement(measurement("s", it)) }.last()
        dao.uploaded("s", last, at = 100, kept = 1, skipped = 2, skipReasons = """{"no_position":2}""")
        dao.uploadFailed("s", "HTTP 503")

        dao.resetUploads()

        val pending = dao.sessionsToUpload().single()
        assertEquals(0, pending.uploadedCount)
        with(pending.session) {
            assertEquals(0L, uploadedSeq)
            assertEquals(null, lastUploadAt)
            assertEquals(null, lastUploadError)
            assertEquals(0L, serverKept)
            assertEquals(0L, serverSkipped)
            assertEquals(null, serverSkipReasons)
        }
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
    fun `direct places group cells and fixless rows and keep only this radio's direct history on these settings`() =
        runTest {
            // Exercise the real SQL and the recorder's fake against the same evidence.
            for (subject in listOf(dao, FakeSurveyDao())) {
                subject.insertSession(session("old", startedAt = 1))
                subject.insertSession(session("new", startedAt = 50))
                subject.insertSession(session("other radio", startedAt = 1, receiverNodeId = 9))
                subject.insertMeasurement(measurement("old", 10, sender = 0x102, relayByte = 0x02, latitude = 1.0))
                subject.insertMeasurement(measurement("new", 55, sender = 0x102, relayByte = 0x02, latitude = 1.0002))
                subject.insertMeasurement(measurement("new", 54, sender = 0x102, relayByte = 0x02, latitude = -0.001))
                subject.insertMeasurement(measurement("new", 59, sender = 0x102, relayByte = 0x02))
                subject.insertMeasurement(measurement("new", 60, sender = 0x102, relayByte = 0x02))
                subject.insertMeasurement(measurement("new", 61, sender = 0x200, relayByte = 0xFF)) // 00 maps to FF.
                subject.insertMeasurement(measurement("new", 62, sender = 0x103, hopLimit = 2, relayByte = 0x03))
                subject.insertMeasurement(
                    measurement("new", 63, sender = 0x104, relayByte = 0x3F),
                ) // Another node's byte.
                subject.insertMeasurement(measurement("new", 64, sender = 0x105)) // No relay byte.
                subject.insertMeasurement(measurement("old", 9, sender = 0x106, relayByte = 0x06)) // Before the cutoff.
                subject.insertMeasurement(measurement("other radio", 70, sender = 0x107, relayByte = 0x07))
                // The same radio on another network: each setting that differs keeps its senders out.
                subject.insertSession(session("other factor", startedAt = 1, spreadingFactor = 9))
                subject.insertSession(session("other bandwidth", startedAt = 1, bandwidthKhz = 125f))
                subject.insertSession(session("other frequency", startedAt = 1, frequencyMhz = 869.4625f))
                subject.insertSession(session("no frequency", startedAt = 1, frequencyMhz = null))
                subject.insertMeasurement(measurement("other factor", 71, sender = 0x108, relayByte = 0x08))
                subject.insertMeasurement(measurement("other bandwidth", 72, sender = 0x109, relayByte = 0x09))
                subject.insertMeasurement(measurement("other frequency", 73, sender = 0x10A, relayByte = 0x0A))
                subject.insertMeasurement(measurement("no frequency", 74, sender = 0x10B, relayByte = 0x0B))

                val places = subject.directPlaces(session("new", startedAt = 50), sinceMs = 10)
                // A session without a frequency matches only others without one.
                assertEquals(
                    listOf(DirectPlace(0x10B, 74, null, null)),
                    subject.directPlaces(session("no frequency", startedAt = 1, frequencyMhz = null), sinceMs = 10),
                )

                assertEquals(
                    setOf(
                        DirectPlace(0x102, 55, 500, 1000),
                        DirectPlace(0x102, 54, -1, 1000),
                        DirectPlace(0x102, 60, null, null),
                        DirectPlace(0x200, 61, null, null),
                    ),
                    places.toSet(),
                )
                assertEquals(4, places.size)
                val neighbours = DirectNeighbours(places.map { it.toDirectSender() })
                assertEquals(
                    DirectSender(0x102, 60, mapOf(DirectCell(500, 1000) to 55L, DirectCell(-1, 1000) to 54L)),
                    neighbours.all[0x102],
                )
                assertEquals(DirectSender(0x200, 61), neighbours.all[0x200])
            }
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
