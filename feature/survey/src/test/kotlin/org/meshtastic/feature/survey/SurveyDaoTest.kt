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
import kotlin.test.assertNull
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
        rssi: Int? = null,
        snr: Float? = null,
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
        rxSnr = snr,
        rxRssi = rssi,
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
    fun `news count distinct cells, relays and directs including fixless, and forget SigSleuth with its server`() =
        runTest {
            dao.insertSession(session("s", startedAt = 1))
            dao.insertSession(session("other", startedAt = 2))

            fun row(heardAt: Long, cell: Int?, relayByte: Int, hopLimit: Int = 2, localNew: Int? = null) =
                measurement("s", heardAt, hopLimit = hopLimit, relayByte = relayByte)
                    .copy(cellX = cell, cellY = cell, localNew = localNew)
            val seqs =
                listOf(
                    // New to this phone too: cell 1, and byte 1A.
                    row(1, cell = 1, relayByte = 0x1A, localNew = SurveyNews.NEW_CELL),
                    row(2, cell = 1, relayByte = 0x1A),
                    // Sender 2 heard directly: its own byte, no hop used.
                    row(3, cell = 1, relayByte = 0x02, hopLimit = 3),
                    row(4, cell = 2, relayByte = 0x1A),
                    // No cell: SigSleuth would not keep it, but its relay byte still counts locally.
                    row(5, cell = null, relayByte = 0x1B),
                )
                    .map { dao.insertMeasurement(it) }
            dao.insertMeasurement(measurement("other", 6, relayByte = 0x1C).copy(cellX = 1, cellY = 1))
            // Byte 1A in two cells is one relay; fixless byte 1B counts too. No earlier session: all are new.
            val counted =
                SurveyNews(cells = 2, relays = 3, direct = 1, localNewCells = 1, localNewRelays = 3, localNewDirect = 1)
            assertEquals(counted, dao.recountNews("s"))

            // Two batches called cell 1 new; it counts once.
            val cell = SurveyNews.NEW_CELL
            dao.markNew("s", mapOf(seqs[0] to cell, seqs[3] to 0))
            // The progress and the flags of a batch are saved together.
            val flags = mapOf(seqs[1] to cell, seqs[2] to 0)
            dao.batchUploaded(
                "s",
                seqs[2],
                at = 100,
                kept = 3,
                skipped = 0,
                skipReasons = null,
                flags = flags,
                answers = emptyList(),
            )
            assertEquals(seqs[2], dao.sessions().first().single { it.session.id == "s" }.session.uploadedSeq)
            val news = counted.copy(newCells = 1)
            assertEquals(news, dao.recountNews("s"))
            assertEquals(news, dao.sessions().first().single { it.session.id == "s" }.session.news)

            // What is new to this phone stays.
            dao.resetUploads()
            assertEquals(counted, dao.countNews("s"))
            assertEquals(counted, dao.sessions().first().single { it.session.id == "s" }.session.news)
        }

    @Test
    fun `cells and relay lists come from earlier sessions on the same network, and news count what is new to you`() =
        runTest {
            // Private, it still counts.
            dao.insertSession(session("old", startedAt = 1, upload = false))
            dao.insertSession(session("other network", startedAt = 1, spreadingFactor = 12))
            // Another channel on the same preset; a frequency the app rounded, or none, is the same network.
            dao.insertSession(session("other channel", startedAt = 1, frequencyMhz = 869.9f))
            dao.insertSession(session("rounded", startedAt = 1, frequencyMhz = 869.5251f))
            dao.insertSession(session("no frequency", startedAt = 1, frequencyMhz = null))
            val now = session("now", startedAt = 2, upload = false)
            dao.insertSession(now)
            dao.insertSession(session("later", startedAt = 3))

            fun row(sessionId: String, relayByte: Int, cell: Int? = 1, hopLimit: Int = 2, localNew: Int? = null) =
                measurement(sessionId, heardAt = 1, hopLimit = hopLimit, relayByte = relayByte)
                    .copy(cellX = cell, cellY = cell, localNew = localNew)
            dao.insertMeasurement(row("old", 0x1A))
            dao.insertMeasurement(row("old", 0x1A))
            // Sender 2 heard directly: its own byte, no hop used.
            dao.insertMeasurement(row("old", 0x02, hopLimit = 3))
            // Known on the network even in another cell or without a cell.
            dao.insertMeasurement(row("old", 0x1D, cell = 2))
            dao.insertMeasurement(row("old", 0x20, cell = null))
            dao.insertMeasurement(row("other network", 0x1B))
            dao.insertMeasurement(row("other channel", 0x1B))
            dao.insertMeasurement(row("rounded", 0x1E))
            dao.insertMeasurement(row("no frequency", 0x1F))
            dao.insertMeasurement(row("later", 0x1C))

            assertTrue(dao.hasCell(now, SurveyCell(1, 1)))
            assertTrue(dao.hasCell(now, SurveyCell(2, 2)))
            assertFalse(dao.hasCell(now, SurveyCell(3, 3)))
            assertEquals(EarlierKeys(setOf(0x1A, 0x02, 0x1D, 0x20, 0x1E, 0x1F), setOf(2L)), dao.earlierKeys(now))

            dao.insertMeasurement(row("now", 0x1A, localNew = 0))
            dao.insertMeasurement(row("now", 0x1B, localNew = 0))
            dao.insertMeasurement(row("now", 0x1B, localNew = 0))
            dao.insertMeasurement(row("now", 0x1A, cell = 3, localNew = SurveyNews.NEW_CELL))
            // Byte 1B new twice counts one new relay; 1A in cell 3 adds only the cell.
            val news = SurveyNews(cells = 2, relays = 2, direct = 0, localNewCells = 1, localNewRelays = 1)
            assertEquals(news, dao.recountNews("now"))
            assertEquals(news, dao.sessions().first().single { it.session.id == "now" }.session.news)
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
    fun `an answer only for fixless rows leaves the map count unknown, not 0`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        val seq = dao.insertMeasurement(measurement("s", 1, relayByte = 0x1A))
        dao.markNew("s", mapOf(seq to 0))
        assertNull(dao.recountNews("s").newCells)
    }

    @Test
    fun `a session a dead process left recording ends at its last measurement and is counted`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        dao.insertMeasurement(measurement("s", 42, relayByte = 0x1A).copy(cellX = 1, cellY = 1))
        dao.insertSession(session("stopped", startedAt = 2).copy(status = SurveyStatus.STOPPED))

        dao.recoverRecording()

        val summaries = dao.sessions().first()
        val summary = summaries.single { it.session.id == "s" }
        assertEquals(SurveyStatus.INTERRUPTED, summary.session.status)
        assertEquals(42L, summary.session.endedAt)
        assertEquals(1, summary.measurementCount)
        // Its row carries no bits, but byte 1A is the first on the phone: its session list makes it new.
        assertEquals(SurveyNews(cells = 1, relays = 1, direct = 0, localNewRelays = 1), summary.session.news)
        assertNull(summaries.single { it.session.id == "stopped" }.session.news)
    }

    @Test
    fun `direct places group cells and fixless rows and keep only this radio's direct history on this network`() =
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
                // The same radio on another network: each setting that differs keeps its senders out. The
                // frequency differs by a whole slot; one within half a bandwidth, or none, is the same network.
                subject.insertSession(session("other factor", startedAt = 1, spreadingFactor = 9))
                subject.insertSession(session("other bandwidth", startedAt = 1, bandwidthKhz = 125f))
                subject.insertSession(session("other frequency", startedAt = 1, frequencyMhz = 869.275f))
                subject.insertSession(session("rounded frequency", startedAt = 1, frequencyMhz = 869.52f))
                subject.insertSession(session("no frequency", startedAt = 1, frequencyMhz = null))
                subject.insertMeasurement(measurement("other factor", 71, sender = 0x108, relayByte = 0x08))
                subject.insertMeasurement(measurement("other bandwidth", 72, sender = 0x109, relayByte = 0x09))
                subject.insertMeasurement(measurement("other frequency", 73, sender = 0x10A, relayByte = 0x0A))
                subject.insertMeasurement(measurement("no frequency", 74, sender = 0x10B, relayByte = 0x0B))
                subject.insertMeasurement(measurement("rounded frequency", 75, sender = 0x10C, relayByte = 0x0C))

                val places = subject.directPlaces(session("new", startedAt = 50), sinceMs = 10)
                // A session without a frequency matches every frequency on its settings.
                assertEquals(
                    setOf(0x102L, 0x200L, 0x10AL, 0x10BL, 0x10CL),
                    subject
                        .directPlaces(session("no frequency", startedAt = 1, frequencyMhz = null), sinceMs = 10)
                        .map { it.nodeId }
                        .toSet(),
                )

                assertEquals(
                    setOf(
                        DirectPlace(0x102, 55, 500, 1000),
                        DirectPlace(0x102, 54, -1, 1000),
                        DirectPlace(0x102, 60, null, null),
                        DirectPlace(0x200, 61, null, null),
                        DirectPlace(0x10B, 74, null, null),
                        DirectPlace(0x10C, 75, null, null),
                    ),
                    places.toSet(),
                )
                assertEquals(6, places.size)
                // An ended session's history stops where it started, as the recorder's did.
                assertEquals(
                    setOf(DirectPlace(0x102, 10, 500, 1000), DirectPlace(0x102, 54, -1, 1000)),
                    subject.directPlaces(session("new", startedAt = 50), sinceMs = 10, beforeMs = 55).toSet(),
                )
                val neighbours = DirectNeighbours(places.map { it.toDirectSender() })
                assertEquals(
                    DirectSender(0x102, 60, mapOf(DirectCell(500, 1000) to 55L, DirectCell(-1, 1000) to 54L)),
                    neighbours.all[0x102],
                )
                assertEquals(DirectSender(0x200, 61), neighbours.all[0x200])
            }
        }

    @Test
    fun `direct places keep each cell's strongest signal, ignoring RSSI 0, and none without RSSI`() = runTest {
        for (subject in listOf(dao, FakeSurveyDao())) {
            subject.insertSession(session("s", startedAt = 1))
            fun direct(at: Long, sender: Long, rssi: Int?, snr: Float?) = measurement(
                "s",
                at,
                sender,
                relayByte = sender.toInt() and 0xFF,
                latitude = 1.0,
                rssi = rssi,
                snr = snr,
            )
            subject.insertMeasurement(direct(10, 0x102, -110, -5f))
            subject.insertMeasurement(direct(11, 0x102, -100, 3f)) // A positive SNR adds nothing.
            subject.insertMeasurement(direct(12, 0x102, 0, 9f)) // RSSI 0 is unset, not the strongest.
            subject.insertMeasurement(direct(13, 0x102, null, null))
            subject.insertMeasurement(direct(14, 0x103, null, -3f))
            subject.insertMeasurement(direct(15, 0x103, 0, null))
            subject.insertMeasurement(direct(16, 0x104, -120, -10.5f))
            subject.insertMeasurement(direct(17, 0x105, -95, null)) // No SNR: RSSI alone.

            val places = subject.directPlaces(session("s", startedAt = 1), sinceMs = 0)

            assertEquals(
                setOf(
                    DirectPlace(0x102, 13, 500, 1000, -100.0),
                    DirectPlace(0x103, 15, 500, 1000, null),
                    DirectPlace(0x104, 16, 500, 1000, -130.5),
                    DirectPlace(0x105, 17, 500, 1000, -95.0),
                ),
                places.toSet(),
            )
            val neighbours = DirectNeighbours(places.map { it.toDirectSender() })
            assertEquals(mapOf(DirectCell(500, 1000) to -100.0), neighbours.all.getValue(0x102).signals)
            assertEquals(emptyMap(), neighbours.all.getValue(0x103).signals)
        }
    }

    @Test
    fun `SigSleuth's answers keep the newest per relay byte and read back whole with their packet's time`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        val first = dao.insertMeasurement(measurement("s", 10, hopLimit = 2, relayByte = 0x1a))
        val second = dao.insertMeasurement(measurement("s", 20, hopLimit = 2, relayByte = 0x1a))
        val unknown =
            ServerAnswer(
                first,
                10,
                0x1a,
                null,
                "unknown",
                null,
                listOf(ServerCandidate(282, "Hill", 1.2), ServerCandidate(794, null, 5.6)),
            )
        val link =
            ServerAnswer(second, 20, 0x1a, 26, "survey_link", "HL", evidence = LinkEvidence(2.3, -2_400_000, true, -97))

        dao.saveAnswers(listOf(unknown.saved("s")))
        assertEquals(listOf(unknown), dao.answers("s"))

        dao.saveAnswers(listOf(link.saved("s")))
        assertEquals(listOf(link), dao.answers("s"))

        val near = ServerAnswer(second, 20, 0x1a, 26, "near_link", "PK", near = NearEvidence(282, "HL", 6.8))
        dao.saveAnswers(listOf(near.saved("s")))
        assertEquals(listOf(near), dao.answers("s"))
    }

    @Test
    fun `saved answers go with their session, and all of them with their server`() = runTest {
        dao.insertSession(session("s", startedAt = 1))
        dao.insertSession(session("other", startedAt = 2))
        val seq = dao.insertMeasurement(measurement("s", 10, hopLimit = 2, relayByte = 0x1a))
        val otherSeq = dao.insertMeasurement(measurement("other", 20, hopLimit = 2, relayByte = 0x1a))
        val answer = ServerAnswer(seq, 10, 0x1a, 26, "reported", null)
        val otherAnswer = ServerAnswer(otherSeq, 20, 0x1a, 26, "reported", null)
        dao.saveAnswers(listOf(answer.saved("s"), otherAnswer.saved("other")))

        dao.deleteSession("s")
        assertEquals(emptyList(), dao.answers("s"))
        assertEquals(listOf(otherAnswer), dao.answers("other"))

        dao.resetUploads()
        assertEquals(emptyList(), dao.answers("other"))
    }

    @Test
    fun `an ended session's replayed relays carry SigSleuth's saved answers`() = runTest {
        val session = session("s", startedAt = 1)
        dao.insertSession(session)
        val seq = dao.insertMeasurement(measurement("s", 10, hopLimit = 2, relayByte = 0x1a))
        dao.insertMeasurement(measurement("s", 20, hopLimit = 2, relayByte = 0x2b))
        val answer = ServerAnswer(seq, 10, 0x1a, 26, "survey_link", "HL")
        dao.saveAnswers(listOf(answer.saved("s")))

        val relays = dao.replayRelays(session, emptyMap(), null).relays

        assertEquals(answer, relays[0x1a]?.server)
        assertNull(relays.getValue(0x2b).server)
    }

    @Test
    fun `session summary relay count includes fixless packets and matches replayed relays`() = runTest {
        for (subject in listOf(dao, FakeSurveyDao())) {
            val mixed = session("mixed", startedAt = 1)
            val fixless = session("fixless", startedAt = 2)
            val empty = session("empty", startedAt = 3)
            subject.insertSession(mixed)
            subject.insertSession(fixless)
            subject.insertSession(empty)
            // With cell, duplicate with cell, fixless with new byte, fixless without relay byte.
            subject.insertMeasurement(
                measurement("mixed", 10, hopLimit = 2, relayByte = 0x1a).copy(cellX = 1, cellY = 1),
            )
            subject.insertMeasurement(
                measurement("mixed", 20, hopLimit = 2, relayByte = 0x1a).copy(cellX = 2, cellY = 2),
            )
            subject.insertMeasurement(measurement("mixed", 30, hopLimit = 2, relayByte = 0x2b))
            subject.insertMeasurement(measurement("mixed", 40, hopLimit = 2, relayByte = null))
            subject.insertMeasurement(measurement("fixless", 50, hopLimit = 2, relayByte = 0x3c))

            val byId = subject.summaries().associateBy { it.session.id }
            assertEquals(2, byId.getValue("mixed").relayCount)
            assertEquals(subject.replayRelays(mixed, emptyMap(), null).relays.size, byId.getValue("mixed").relayCount)
            assertEquals(1, byId.getValue("fixless").relayCount)
            assertEquals(
                subject.replayRelays(fixless, emptyMap(), null).relays.size,
                byId.getValue("fixless").relayCount,
            )
            assertEquals(0, byId.getValue("empty").relayCount)
            assertEquals(subject.replayRelays(empty, emptyMap(), null).relays.size, byId.getValue("empty").relayCount)
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

    @Test
    fun `deleting a session passes what it was first to have to the next session on its network`() = runTest {
        dao.insertSession(session("first", startedAt = 1))
        dao.insertSession(session("other network", startedAt = 2, spreadingFactor = 12))
        dao.insertSession(session("second", startedAt = 3))
        dao.insertSession(session("third", startedAt = 4))
        val cell = SurveyNews.NEW_CELL
        fun row(sessionId: String, heardAt: Long, relayByte: Int, localNew: Int, cellCoord: Int? = 1) =
            measurement(sessionId, heardAt, relayByte = relayByte)
                .copy(cellX = cellCoord, cellY = cellCoord, localNew = localNew)
        dao.insertMeasurement(row("first", 1, 0x1A, cell))
        // Wrong on purpose: a recount that reached another network would set it.
        dao.insertMeasurement(row("other network", 2, 0x1A, 0))
        dao.insertMeasurement(row("second", 3, 0x1A, 0))
        dao.insertMeasurement(row("second", 4, 0x1B, 0))
        // Byte 1A without a cell should still become new when "first" and "second" are deleted.
        dao.insertMeasurement(row("third", 5, 0x1A, 0, cellCoord = null))
        listOf("first", "other network", "second", "third").forEach { dao.recountNews(it) }

        dao.deleteSession("first")

        suspend fun flags(sessionId: String) = dao.measurementsAfter(sessionId, 0, limit = 10).map { it.localNew }
        // Every row in a cell no earlier session had carries the cell bit, as when recording.
        assertEquals(listOf(cell, cell), flags("second"))
        assertEquals(listOf(0), flags("third"))
        assertEquals(listOf(0), flags("other network"))
        val news = dao.sessions().first().associate { it.session.id to it.session.news }
        assertEquals(SurveyNews(1, 2, 0, localNewCells = 1, localNewRelays = 2), news.getValue("second"))
        // Second still had 1A first.
        assertEquals(0, news.getValue("third")?.localNewRelays)

        // Deleting "second" passes byte 1A to "third" even though "third" has no GPS cell.
        dao.deleteSession("second")
        assertEquals(listOf(0), flags("third"))
        assertEquals(
            SurveyNews(0, 1, 0, localNewCells = 0, localNewRelays = 1),
            dao.sessions().first().single { it.session.id == "third" }.session.news,
        )
    }

    @Test
    fun `deleting a session recounts a later one against channels the deleted one does not match`() = runTest {
        // At 62.5 kHz, 869.4 and 869.6 MHz are two channels; a session without a frequency matches both.
        dao.insertSession(session("lower", startedAt = 1, bandwidthKhz = 62.5f, frequencyMhz = 869.4f))
        dao.insertSession(session("upper", startedAt = 2, bandwidthKhz = 62.5f, frequencyMhz = 869.6f))
        dao.insertSession(session("unknown", startedAt = 3, bandwidthKhz = 62.5f, frequencyMhz = null))
        val cell = SurveyNews.NEW_CELL
        fun row(sessionId: String, heardAt: Long, relayByte: Int, localNew: Int) =
            measurement(sessionId, heardAt, relayByte = relayByte).copy(cellX = 1, cellY = 1, localNew = localNew)
        dao.insertMeasurement(row("lower", 1, 0x1A, cell))
        dao.insertMeasurement(row("lower", 2, 0x1B, cell))
        dao.insertMeasurement(row("upper", 3, 0x1A, cell))
        dao.insertMeasurement(row("unknown", 4, 0x1A, 0))
        dao.insertMeasurement(row("unknown", 5, 0x1B, 0))
        listOf("lower", "upper", "unknown").forEach { dao.recountNews(it) }
        suspend fun news(sessionId: String) = dao.sessions().first().single { it.session.id == sessionId }.session.news
        assertEquals(0, news("unknown")?.localNewRelays)

        dao.deleteSession("lower")

        // The cell and byte 1A are still known from "upper"; only "lower" had 1B.
        assertEquals(listOf(0, 0), dao.measurementsAfter("unknown", 0, limit = 10).map { it.localNew })
        assertEquals(SurveyNews(1, 2, 0, localNewCells = 0, localNewRelays = 1), news("unknown"))
        // "upper" is another channel than "lower": its counts stay.
        assertEquals(SurveyNews(1, 1, 0, localNewCells = 1, localNewRelays = 1), news("upper"))
    }

    @Test
    fun `a deleted session's map counts stay in the phone's total until uploads reset`() = runTest {
        dao.insertSession(session("answered", startedAt = 1))
        dao.insertSession(session("unanswered", startedAt = 2))
        val seq = dao.insertMeasurement(measurement("answered", 1, relayByte = 0x1A).copy(cellX = 1, cellY = 1))
        dao.insertMeasurement(measurement("unanswered", 2, relayByte = 0x1A).copy(cellX = 2, cellY = 2))
        dao.markNew("answered", mapOf(seq to SurveyNews.NEW_CELL))

        dao.deleteSession("unanswered")
        assertNull(dao.deleted().first())
        dao.deleteSession("answered")
        assertEquals(SurveyDeleted(newCells = 1), dao.deleted().first())

        dao.resetUploads()
        assertNull(dao.deleted().first())
    }
}
