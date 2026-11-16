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
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Builds the database as an older version from its exported schema, fills it, and opens it with the current
 * [SurveyDatabase]. Room runs the auto-migrations and then checks the tables against the current entities, so a broken
 * migration fails here instead of crashing the app on a phone holding real sessions.
 *
 * Room's MigrationTestHelper does this too, but its Android variant, which host tests get, needs an instrumented
 * device.
 */
class SurveyDatabaseMigrationTest {
    private val dbFile = File.createTempFile("survey-migration", ".db").apply { delete() }

    @AfterTest
    fun cleanUp() {
        dbFile.delete()
        File(dbFile.path + "-wal").delete()
        File(dbFile.path + "-shm").delete()
    }

    @Test
    fun `version 1 sessions and measurements survive the migration to the current version`() = runTest {
        createDatabase(version = 1) {
            execSQL(
                "INSERT INTO survey_session (id, started_at, ended_at, status, receiver_node_id, receiver_name, " +
                    "modem_preset, spreading_factor, bandwidth_khz, frequency_mhz, app_version, uploaded_seq) " +
                    "VALUES ('s', 1000, 2000, 'STOPPED', 1, 'Survey node', 'LONG_FAST', 11, 250.0, 869.525, " +
                    "'test', 1)",
            )
            insertMeasurement()
        }

        withCurrentDatabase { dao ->
            val session = dao.sessions().first().single().session
            assertEquals("Survey node", session.receiverName)
            assertEquals(SurveyStatus.STOPPED, session.status)
            assertEquals(1, session.uploadedSeq)
            assertEquals(0, session.serverKept)
            assertEquals(0, session.serverSkipped)
            assertNull(session.serverSkipReasons)
            assertOldMeasurement(dao)
        }
    }

    @Test
    fun `version 2 rows survive the migration to version 3 and leave the new columns unknown`() = runTest {
        createDatabase(version = 2) {
            execSQL(
                "INSERT INTO survey_session (id, started_at, status, receiver_node_id, receiver_name, " +
                    "spreading_factor, bandwidth_khz, app_version, uploaded_seq, server_kept, server_skipped, " +
                    "server_skip_reasons) " +
                    "VALUES ('s', 1000, 'RECORDING', 1, 'Survey node', 11, 250.0, 'test', 1, 5, 2, " +
                    "'{\"no_position\":2}')",
            )
            insertMeasurement()
        }

        withCurrentDatabase { dao ->
            val session = dao.sessions().first().single().session
            assertEquals(5, session.serverKept)
            assertEquals(2, session.serverSkipped)
            assertEquals("{\"no_position\":2}", session.serverSkipReasons)
            assertOldMeasurement(dao)
        }
    }

    @Test
    fun `version 3 sessions migrate to version 4 as private, so nothing old is sent unasked`() = runTest {
        createDatabase(version = 3) {
            execSQL(
                "INSERT INTO survey_session (id, started_at, status, receiver_node_id, receiver_name, " +
                    "spreading_factor, bandwidth_khz, app_version, uploaded_seq, server_kept, server_skipped) " +
                    "VALUES ('s', 1000, 'STOPPED', 1, 'Survey node', 11, 250.0, 'test', 0, 0, 0)",
            )
            insertMeasurement()
        }

        withCurrentDatabase { dao ->
            val session = dao.sessions().first().single().session
            assertFalse(session.upload)
            assertEquals(emptyList(), dao.sessionsToUpload())
            assertOldMeasurement(dao)
        }
    }

    @Test
    fun `version 4 rows migrate with their cell, new to you, and counted`() = runTest {
        createDatabase(version = 4) {
            execSQL(
                "INSERT INTO survey_session (id, started_at, status, receiver_node_id, receiver_name, " +
                    "spreading_factor, bandwidth_khz, app_version, uploaded_seq, server_kept, server_skipped, " +
                    "upload) VALUES ('s', 1000, 'STOPPED', 1, 'Survey node', 11, 250.0, 'test', 0, 0, 0, 1)",
            )
            insertMeasurement()
        }

        withCurrentDatabase { dao ->
            val row = dao.measurementsAfter("s", afterSeq = 0, limit = 1).single()
            assertEquals(SurveyCell.of(1.5, 2.5), row.cell())
            assertNull(row.serverNew)
            assertEquals(SurveyNews.NEW_CELL or SurveyNews.NEW_NODE, row.localNew)
            assertEquals(
                SurveyNews(cells = 1, relays = 1, direct = 0, localNewCells = 1, localNewRelayCells = 1),
                dao.sessions().first().single().session.news,
            )
            assertOldMeasurement(dao)
        }
    }

    @Test
    fun `version 4 rows learn what is new to you from sessions that started earlier on their settings`() = runTest {
        createDatabase(version = 4) {
            // Started first, though its ID sorts last.
            insertSession("z-early", startedAt = 1000)
            insertSession("other network", startedAt = 1500, spreadingFactor = 12)
            insertSession("other channel", startedAt = 1600, frequencyMhz = 869.9)
            insertSession("a-late", startedAt = 2000)
            insertRow(1, "z-early", relayByte = 0x1A)
            insertRow(2, "other network", relayByte = 0x1B)
            insertRow(8, "other channel", relayByte = 0x1B)
            insertRow(3, "a-late", relayByte = 0x1A)
            insertRow(4, "a-late", relayByte = 0x1B)
            // Sender 2 heard directly in a known cell.
            insertRow(5, "a-late", relayByte = 0x02, hopLimit = 3)
            // A fix too poor for a cell.
            insertRow(7, "a-late", relayByte = 0x1A, accuracyM = 500.0)
        }

        withCurrentDatabase { dao ->
            val cell = SurveyNews.NEW_CELL
            val node = SurveyNews.NEW_NODE
            suspend fun flags(sessionId: String) =
                dao.measurementsAfter(sessionId, afterSeq = 0, limit = 10).map { it.localNew }
            assertEquals(listOf(cell or node), flags("z-early"))
            assertEquals(listOf(cell or node), flags("other network"))
            assertEquals(listOf(cell or node), flags("other channel"))
            assertEquals(listOf(0, node, node or SurveyNews.NEW_DIRECT, null), flags("a-late"))
            assertEquals(
                SurveyNews(1, 3, 1, localNewCells = 0, localNewRelayCells = 1, localNewDirectCells = 1),
                dao.sessions().first().single { it.session.id == "a-late" }.session.news,
            )
        }
    }

    @Test
    fun `the fill compares a session with each earlier one on its own, as recording does`() = runTest {
        createDatabase(version = 4) {
            // At 62.5 kHz, 869.4 and 869.6 MHz are two channels; a session without a frequency matches both.
            insertSession("unknown", startedAt = 1000, bandwidthKhz = 62.5, frequencyMhz = null)
            insertSession("lower", startedAt = 2000, bandwidthKhz = 62.5, frequencyMhz = 869.4)
            insertSession("upper", startedAt = 3000, bandwidthKhz = 62.5, frequencyMhz = 869.6)
            insertRow(1, "unknown", relayByte = 0x1A)
            insertRow(2, "lower", relayByte = 0x1B)
            insertRow(3, "upper", relayByte = 0x1B)
        }

        withCurrentDatabase { dao ->
            val upper = dao.sessions().first().single { it.session.id == "upper" }.session
            val recorded = CellHistory(dao.cellKeys(upper, SurveyCell.of(1.5, 2.5))).newBits(CellKey(0x1B, null))
            // The cell is known from "unknown"; byte 1B only from "lower", another channel.
            assertEquals(SurveyNews.NEW_NODE, recorded)
            assertEquals(listOf(recorded), dao.measurementsAfter("upper", afterSeq = 0, limit = 10).map { it.localNew })
        }
    }

    @Test
    fun `version 5 counts by pairs are counted again by cell, and deleted sessions keep their map cells`() = runTest {
        createDatabase(version = 5) {
            insertSession("s", startedAt = 1000)
            // Version 5's counts by cell and key, stale once read by cell.
            execSQL(
                "UPDATE survey_session SET news_cells = 2, news_nodes = 3, news_direct = 1, news_new_cells = 1, " +
                    "news_new_nodes = 3, news_new_direct = 1, news_local_new_cells = 2, " +
                    "news_local_new_nodes = 3, news_local_new_direct = 1",
            )
            val cell = SurveyNews.NEW_CELL
            val node = SurveyNews.NEW_NODE
            // Byte 1A in two cells; sender 2 heard directly in the second.
            insertCellRow(1, cellX = 1, relayByte = 0x1A, localNew = cell or node, serverNew = cell or node)
            insertCellRow(2, cellX = 2, relayByte = 0x1A, localNew = cell or node, serverNew = 0)
            insertCellRow(3, cellX = 2, relayByte = 0x02, hopLimit = 3, localNew = node or SurveyNews.NEW_DIRECT)
            execSQL("INSERT INTO survey_deleted (id, new_cells, new_nodes, new_direct) VALUES (0, 4, 7, 2)")
        }

        withCurrentDatabase { dao ->
            assertEquals(
                SurveyNews(
                    cells = 2,
                    relays = 2,
                    direct = 1,
                    newCells = 1,
                    localNewCells = 2,
                    localNewRelayCells = 2,
                    localNewDirectCells = 1,
                ),
                dao.sessions().first().single().session.news,
            )
            assertEquals(SurveyDeleted(newCells = 4), dao.deleted().first())
            assertEquals(1, dao.countDirectSenders())
        }
    }

    /** A version 5 row of session "s" from sender 2, with its cell and what is new. */
    @Suppress("LongParameterList")
    private fun androidx.sqlite.SQLiteConnection.insertCellRow(
        seq: Long,
        cellX: Int,
        relayByte: Int,
        hopLimit: Int = 2,
        localNew: Int,
        serverNew: Int? = null,
    ) = execSQL(
        "INSERT INTO survey_measurement (seq, session_id, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, " +
            "sender_node_id, recipient_node_id, packet_id, hop_start, hop_limit, relay_byte, cell_x, cell_y, " +
            "local_new, server_new) VALUES ($seq, 's', 1500, 1.5, 2.5, 4.0, 300, 2, 4294967295, $seq, 3, " +
            "$hopLimit, $relayByte, $cellX, $cellX, $localNew, ${serverNew ?: "NULL"})",
    )

    private fun androidx.sqlite.SQLiteConnection.insertSession(
        id: String,
        startedAt: Long,
        spreadingFactor: Int = 11,
        bandwidthKhz: Double = 250.0,
        frequencyMhz: Double? = 869.525,
    ) = execSQL(
        "INSERT INTO survey_session (id, started_at, status, receiver_node_id, receiver_name, spreading_factor, " +
            "bandwidth_khz, frequency_mhz, app_version, uploaded_seq, server_kept, server_skipped, upload) " +
            "VALUES ('$id', $startedAt, 'STOPPED', 1, 'Survey node', $spreadingFactor, $bandwidthKhz, " +
            "${frequencyMhz ?: "NULL"}, 'test', 0, 0, 0, 0)",
    )

    /** A version 4 row from sender 2 at 1.5, 2.5. */
    private fun androidx.sqlite.SQLiteConnection.insertRow(
        seq: Long,
        sessionId: String,
        relayByte: Int,
        hopLimit: Int = 2,
        accuracyM: Double = 4.0,
    ) = execSQL(
        "INSERT INTO survey_measurement (seq, session_id, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, " +
            "sender_node_id, recipient_node_id, packet_id, hop_start, hop_limit, relay_byte) " +
            "VALUES ($seq, '$sessionId', 1500, 1.5, 2.5, $accuracyM, 300, 2, 4294967295, $seq, 3, $hopLimit, " +
            "$relayByte)",
    )

    private fun androidx.sqlite.SQLiteConnection.insertMeasurement() = execSQL(
        "INSERT INTO survey_measurement (seq, session_id, heard_at_ms, latitude, longitude, accuracy_m, fix_age_ms, " +
            "sender_node_id, recipient_node_id, packet_id, rx_snr, rx_rssi, hop_start, hop_limit, relay_byte) " +
            "VALUES (1, 's', 1500, 1.5, 2.5, 4.0, 300, 2, 4294967295, 77, -3.25, -110, 3, 2, 171)",
    )

    private suspend fun assertOldMeasurement(dao: SurveyDao) {
        val row = dao.measurementsAfter("s", afterSeq = 0, limit = 10).single()
        assertEquals(1500, row.heardAtMs)
        assertEquals(1.5, row.latitude)
        assertEquals(2.5, row.longitude)
        assertEquals(300, row.fixAgeMs)
        assertEquals(4_294_967_295, row.recipientNodeId)
        assertEquals(-3.25f, row.rxSnr)
        assertEquals(-110, row.rxRssi)
        assertEquals(171, row.relayByte)
        assertNull(row.okToMqtt)
        assertNull(row.defaultKey)
    }

    /** Creates the database exactly as [version]'s exported schema describes it, then runs [fill] on it. */
    private fun createDatabase(version: Int, fill: androidx.sqlite.SQLiteConnection.() -> Unit) {
        val schema = File("schemas/${SurveyDatabase::class.qualifiedName}/$version.json")
        val database = Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject
        BundledSQLiteDriver().open(dbFile.path).use { connection ->
            for (entity in database.getValue("entities").jsonArray.map { it.jsonObject }) {
                val table = entity.getValue("tableName").jsonPrimitive.content
                val indices = (entity["indices"] as? JsonArray).orEmpty().map { it.jsonObject.getValue("createSql") }
                (listOf(entity.getValue("createSql")) + indices).forEach {
                    connection.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $version")
            connection.fill()
        }
    }

    private suspend fun withCurrentDatabase(check: suspend (SurveyDao) -> Unit) {
        val database =
            Room.databaseBuilder<SurveyDatabase>(name = dbFile.path, factory = { SurveyDatabase_Impl() })
                .setDriver(BundledSQLiteDriver())
                .build()
        try {
            check(database.surveyDao())
        } finally {
            database.close()
        }
    }
}
