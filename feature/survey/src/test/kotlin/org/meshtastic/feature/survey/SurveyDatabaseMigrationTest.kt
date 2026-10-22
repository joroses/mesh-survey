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
