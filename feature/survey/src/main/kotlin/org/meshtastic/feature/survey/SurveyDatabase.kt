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

import androidx.room3.AutoMigration
import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

/** Why a session is no longer recording. RECORDING is the only live status. */
enum class SurveyStatus {
    RECORDING,
    STOPPED,
    NODE_CHANGED,
    LORA_CHANGED,
    RADIO_LOST,
    INTERRUPTED,
}

/**
 * One recording run with one survey node and one set of LoRa settings. Node numbers are stored unsigned, as SigSleuth
 * expects them. [uploadedSeq] is the highest measurement [SurveyMeasurement.seq] SigSleuth has acknowledged.
 */
@Entity(tableName = "survey_session")
data class SurveySession(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val status: SurveyStatus = SurveyStatus.RECORDING,
    @ColumnInfo(name = "receiver_node_id") val receiverNodeId: Long,
    @ColumnInfo(name = "receiver_name") val receiverName: String,
    /** The preset's name, or null when the node uses custom LoRa settings. */
    @ColumnInfo(name = "modem_preset") val modemPreset: String?,
    @ColumnInfo(name = "spreading_factor") val spreadingFactor: Int,
    /** The bandwidth the radio uses, in kHz (62.5, not the config's code 62). */
    @ColumnInfo(name = "bandwidth_khz") val bandwidthKhz: Float,
    /** The centre frequency in MHz, as the app's LoRa settings show it; null when the node has no region. */
    @ColumnInfo(name = "frequency_mhz") val frequencyMhz: Float?,
    @ColumnInfo(name = "app_version") val appVersion: String,
    @ColumnInfo(name = "uploaded_seq") val uploadedSeq: Long = 0,
    @ColumnInfo(name = "last_upload_at") val lastUploadAt: Long? = null,
    @ColumnInfo(name = "last_upload_error") val lastUploadError: String? = null,
    /** Uploaded measurements SigSleuth kept, counting those it already had. */
    @ColumnInfo(name = "server_kept", defaultValue = "0") val serverKept: Long = 0,
    /** Uploaded measurements SigSleuth could not use; [serverSkipReasons] says why. */
    @ColumnInfo(name = "server_skipped", defaultValue = "0") val serverSkipped: Long = 0,
    /** SigSleuth's skip reasons and their counts, as a JSON object (`{"no_position": 180}`); null before any skip. */
    @ColumnInfo(name = "server_skip_reasons") val serverSkipReasons: String? = null,
)

/**
 * One packet the survey node heard, with where the phone was. These are exactly the fields of SigSleuth's upload; a
 * missing SNR, RSSI, position or relay byte is null, and a 0 SNR is a real reading.
 */
@Entity(tableName = "survey_measurement", indices = [Index(value = ["session_id", "seq"])])
data class SurveyMeasurement(
    /** Increases within a session, so it doubles as the upload's `seq`. */
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "heard_at_ms") val heardAtMs: Long,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Float?,
    @ColumnInfo(name = "fix_age_ms") val fixAgeMs: Long?,
    @ColumnInfo(name = "sender_node_id") val senderNodeId: Long,
    @ColumnInfo(name = "recipient_node_id") val recipientNodeId: Long,
    @ColumnInfo(name = "packet_id") val packetId: Long,
    @ColumnInfo(name = "rx_snr") val rxSnr: Float?,
    @ColumnInfo(name = "rx_rssi") val rxRssi: Int?,
    @ColumnInfo(name = "hop_start") val hopStart: Int,
    @ColumnInfo(name = "hop_limit") val hopLimit: Int,
    @ColumnInfo(name = "relay_byte") val relayByte: Int?,
) {
    override fun toString(): String = "SurveyMeasurement(seq=$seq, location redacted)"
}

/** A session with its measurement counts and newest measurement seq, for the session list and uploads. */
data class SurveySessionSummary(
    @Embedded val session: SurveySession,
    @ColumnInfo(name = "measurement_count") val measurementCount: Int,
    @ColumnInfo(name = "uploaded_count") val uploadedCount: Int,
    @ColumnInfo(name = "last_seq") val lastSeq: Long,
)

private const val SUMMARY =
    "SELECT s.*, COUNT(m.seq) AS measurement_count, COALESCE(MAX(m.seq), 0) AS last_seq, " +
        "COALESCE(SUM(m.seq <= s.uploaded_seq), 0) AS uploaded_count " +
        "FROM survey_session s LEFT JOIN survey_measurement m ON m.session_id = s.id"

@Dao
@Suppress("TooManyFunctions") // One query per need; they are all small.
interface SurveyDao {
    @Insert suspend fun insertSession(session: SurveySession)

    @Insert suspend fun insertMeasurement(measurement: SurveyMeasurement): Long

    @Query("$SUMMARY GROUP BY s.id ORDER BY s.started_at DESC")
    fun sessions(): Flow<List<SurveySessionSummary>>

    @Query("$SUMMARY GROUP BY s.id HAVING MAX(m.seq) > s.uploaded_seq ORDER BY s.started_at")
    suspend fun sessionsToUpload(): List<SurveySessionSummary>

    @Query(
        "SELECT * FROM survey_measurement WHERE session_id = :sessionId AND seq > :afterSeq ORDER BY seq LIMIT :limit",
    )
    suspend fun measurementsAfter(sessionId: String, afterSeq: Long, limit: Int): List<SurveyMeasurement>

    @Query("UPDATE survey_session SET status = :status, ended_at = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: String, status: SurveyStatus, endedAt: Long)

    /** Marks sessions a dead process left recording; they end at their last measurement. */
    @Query(
        "UPDATE survey_session SET status = 'INTERRUPTED', ended_at = COALESCE(" +
            "(SELECT MAX(heard_at_ms) FROM survey_measurement WHERE session_id = survey_session.id), started_at) " +
            "WHERE status = 'RECORDING'",
    )
    suspend fun interruptRecording()

    @Query(
        "UPDATE survey_session SET uploaded_seq = MAX(uploaded_seq, :uploadedSeq), last_upload_at = :at, " +
            "last_upload_error = NULL, server_kept = :kept, server_skipped = :skipped, " +
            "server_skip_reasons = :skipReasons WHERE id = :sessionId",
    )
    @Suppress("LongParameterList")
    suspend fun uploaded(
        sessionId: String,
        uploadedSeq: Long,
        at: Long,
        kept: Long,
        skipped: Long,
        skipReasons: String?,
    )

    @Query("UPDATE survey_session SET last_upload_error = :error WHERE id = :sessionId")
    suspend fun uploadFailed(sessionId: String, error: String)

    @Query("DELETE FROM survey_measurement WHERE session_id = :sessionId")
    suspend fun deleteMeasurements(sessionId: String)

    @Query("DELETE FROM survey_session WHERE id = :sessionId")
    suspend fun deleteSessionRow(sessionId: String)

    @Transaction
    suspend fun deleteSession(sessionId: String) {
        deleteMeasurements(sessionId)
        deleteSessionRow(sessionId)
    }
}

/**
 * The survey's own database, `signal_survey.db`. It stays apart from the per-device app database, which is swapped when
 * the user picks another radio and migrated often upstream.
 */
@Database(
    entities = [SurveySession::class, SurveyMeasurement::class],
    version = 2,
    exportSchema = true,
    // 2: what SigSleuth kept and skipped of each session.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class SurveyDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
}
