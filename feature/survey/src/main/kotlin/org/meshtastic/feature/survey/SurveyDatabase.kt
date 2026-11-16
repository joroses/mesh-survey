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
import kotlinx.coroutines.flow.map

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
 * expects them. [uploadedSeq] is the highest measurement [SurveyMeasurement.seq] SigSleuth has acknowledged. Only a
 * session with [upload] set is ever sent.
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
    /**
     * Whether the user lets this session go to SigSleuth: set when it starts with automatic uploads on or the user
     * uploads it, cleared when automatic uploads are turned off while it records. Sessions from before version 4 start
     * private.
     */
    @ColumnInfo(defaultValue = "0") val upload: Boolean = false,
    /** What the session measured and added to SigSleuth's map, as last counted; null before the first count. */
    @Embedded(prefix = "news_") val news: SurveyNews? = null,
)

/**
 * What a session measured where it had a cell: its distinct cells, relay bytes and senders heard directly; the cells
 * SigSleuth's replies called new to its map (null until a reply says, a cell counting once whichever batch said so);
 * and the cells that were new to this phone, or where it heard a relay byte or a direct sender for the first time there
 * ([SurveyMeasurement.localNew]). Every "new" count is of cells, so sessions' counts add up.
 */
data class SurveyNews(
    val cells: Int,
    val relays: Int,
    val direct: Int,
    @ColumnInfo(name = "new_cells") val newCells: Int? = null,
    @ColumnInfo(name = "local_new_cells") val localNewCells: Int = 0,
    @ColumnInfo(name = "local_new_relay_cells") val localNewRelayCells: Int = 0,
    @ColumnInfo(name = "local_new_direct_cells") val localNewDirectCells: Int = 0,
) {
    companion object {
        /**
         * [SurveyMeasurement.localNew] bits; [SurveyMeasurement.serverNew] uses only [NEW_CELL]. The `NEWS` query
         * repeats them.
         */
        const val NEW_CELL = 1
        const val NEW_NODE = 2
        const val NEW_DIRECT = 4
    }
}

/**
 * What deleted sessions had added to SigSleuth's map, so the phone's total never drops when sessions are tidied away.
 * One row, written when a session SigSleuth answered for is deleted; [SurveyDao.resetUploads] clears it with the rest
 * of the server's answers.
 */
@Entity(tableName = "survey_deleted")
data class SurveyDeleted(@PrimaryKey val id: Int = 0, @ColumnInfo(name = "new_cells") val newCells: Int = 0)

/**
 * One packet the survey node heard, with where the phone was. These are the fields of SigSleuth's upload, plus its cell
 * and what SigSleuth said it adds; a missing SNR, RSSI, position or relay byte is null, and a 0 SNR is a real reading.
 */
@Entity(
    tableName = "survey_measurement",
    indices = [Index(value = ["session_id", "seq"]), Index(value = ["cell_x", "cell_y"])],
)
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
    /** The sender's OK to MQTT bit; null when the survey node could not decode the packet. */
    @ColumnInfo(name = "ok_to_mqtt") val okToMqtt: Boolean? = null,
    /** Whether the packet's channel uses the default key; null when the survey node could not decode the packet. */
    @ColumnInfo(name = "default_key") val defaultKey: Boolean? = null,
    /** Its SigSleuth map cell ([SurveyCell]); null when SigSleuth would not keep it (no fix, or a poor one). */
    @ColumnInfo(name = "cell_x") val cellX: Int? = null,
    @ColumnInfo(name = "cell_y") val cellY: Int? = null,
    /**
     * Whether SigSleuth's upload reply said it adds a cell to the map, as [SurveyNews.NEW_CELL] or 0; null until a
     * reply says. Rows saved by earlier versions may also carry the other bits, which nothing reads.
     */
    @ColumnInfo(name = "server_new") val serverNew: Int? = null,
    /**
     * What it adds to this phone's sessions that started earlier on the same spreading factor and bandwidth, as
     * [SurveyNews] bits; null without a cell.
     */
    @ColumnInfo(name = "local_new") val localNew: Int? = null,
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

/**
 * One sender's latest direct reception in a cell and its strongest [signalOf] there (null when no row had RSSI); a
 * fixless reception has two null cell coordinates.
 */
data class DirectPlace(
    val nodeId: Long,
    val lastDirectMs: Long,
    val latitudeCell: Int?,
    val longitudeCell: Int?,
    val strongestSignal: Double? = null,
) {
    fun toDirectSender(): DirectSender {
        val cell = latitudeCell?.let { lat -> longitudeCell?.let { lon -> DirectCell(lat, lon) } }
        return DirectSender(
            nodeId,
            lastDirectMs,
            cell?.let { mapOf(it to lastDirectMs) }.orEmpty(),
            cell?.let { c -> strongestSignal?.let { mapOf(c to it) } }.orEmpty(),
        )
    }
}

/** Sessions `s` on the network of :spreadingFactor, :bandwidthKhz and :frequencyMhz, as [SurveyNetwork.same]. */
internal const val SAME_NETWORK =
    "s.spreading_factor = :spreadingFactor AND s.bandwidth_khz = :bandwidthKhz " +
        "AND (s.frequency_mhz IS NULL OR :frequencyMhz IS NULL " +
        "OR abs(s.frequency_mhz - :frequencyMhz) < s.bandwidth_khz / 2000)"

/** [isDirect] in SQL, with [relayByteOf]'s `00` → `FF` mapping as the CASE. */
internal const val IS_DIRECT =
    "hop_start = hop_limit AND relay_byte = CASE WHEN sender_node_id % 256 = 0 THEN 255 ELSE sender_node_id % 256 END"
private const val CELL = "cell_x || ',' || cell_y"

/** [SurveyNews]'s columns, counted over one session's measurements that have a cell. */
internal const val NEWS_COLUMNS =
    "COUNT(DISTINCT $CELL) AS cells, " +
        "COUNT(DISTINCT relay_byte) AS relays, " +
        "COUNT(DISTINCT CASE WHEN $IS_DIRECT THEN sender_node_id END) AS direct, " +
        "CASE WHEN COUNT(server_new) > 0 THEN COUNT(DISTINCT CASE WHEN server_new & 1 THEN $CELL END) END " +
        "AS new_cells, " +
        "COUNT(DISTINCT CASE WHEN local_new & 1 THEN $CELL END) AS local_new_cells, " +
        "COUNT(DISTINCT CASE WHEN local_new & 2 THEN $CELL END) AS local_new_relay_cells, " +
        "COUNT(DISTINCT CASE WHEN local_new & 4 THEN $CELL END) AS local_new_direct_cells"

/** [SurveyNews]'s columns on `survey_session`, in [NEWS_COLUMNS]' order. */
internal const val NEWS_TARGET =
    "(news_cells, news_relays, news_direct, news_new_cells, news_local_new_cells, news_local_new_relay_cells, " +
        "news_local_new_direct_cells)"

private const val NEWS =
    "SELECT $NEWS_COLUMNS FROM survey_measurement WHERE session_id = :sessionId AND cell_x IS NOT NULL"

/** Measurements `m` with a cell as [CellRow]s; the migration reads the columns in this order. */
internal const val CELL_ROWS =
    "SELECT m.seq, m.session_id AS sessionId, s.started_at AS startedAt, s.spreading_factor AS spreadingFactor, " +
        "s.bandwidth_khz AS bandwidthKhz, s.frequency_mhz AS frequencyMhz, m.cell_x AS x, m.cell_y AS y, " +
        "m.relay_byte AS relayByte, CASE WHEN $IS_DIRECT THEN m.sender_node_id END AS directSender, " +
        "m.local_new AS localNew FROM survey_measurement m JOIN survey_session s ON s.id = m.session_id " +
        "WHERE m.cell_x IS NOT NULL"

/** [CELL_ROWS] in session start order, a session's rows together, as [withLocalNew] needs them. */
internal const val START_ORDER = " ORDER BY s.started_at, s.id, m.seq"

private const val SUMMARY =
    "SELECT s.*, COUNT(m.seq) AS measurement_count, COALESCE(MAX(m.seq), 0) AS last_seq, " +
        "COALESCE(SUM(m.seq <= s.uploaded_seq), 0) AS uploaded_count " +
        "FROM survey_session s LEFT JOIN survey_measurement m ON m.session_id = s.id"

@Dao
@Suppress("TooManyFunctions") // One query per need; they are all small.
interface SurveyDao {
    @Insert suspend fun insertSession(session: SurveySession)

    @Insert suspend fun insertMeasurement(measurement: SurveyMeasurement): Long

    @Query("SELECT * FROM survey_session ORDER BY started_at DESC")
    fun sessionRows(): Flow<List<SurveySession>>

    @Query("$SUMMARY GROUP BY s.id ORDER BY s.started_at DESC")
    suspend fun summaries(): List<SurveySessionSummary>

    /**
     * The session list, newest first. It follows the session table only: a flow on [SUMMARY] would count every
     * measurement again after each insert. So a recording session's counts change only when its row does (an upload,
     * the end); until then the recorder has the live count.
     */
    fun sessions(): Flow<List<SurveySessionSummary>> = sessionRows().map { summaries() }

    @Query("$SUMMARY WHERE s.upload GROUP BY s.id HAVING MAX(m.seq) > s.uploaded_seq ORDER BY s.started_at")
    suspend fun sessionsToUpload(): List<SurveySessionSummary>

    /** Whether [sessionId] may still be sent; false once it is private again or deleted. */
    @Query("SELECT EXISTS(SELECT 1 FROM survey_session WHERE id = :sessionId AND upload)")
    suspend fun uploadAllowed(sessionId: String): Boolean

    @Query("UPDATE survey_session SET upload = :upload WHERE id = :sessionId")
    suspend fun setUpload(sessionId: String, upload: Boolean)

    @Query(
        "SELECT * FROM survey_measurement WHERE session_id = :sessionId AND seq > :afterSeq ORDER BY seq LIMIT :limit",
    )
    suspend fun measurementsAfter(sessionId: String, afterSeq: Long, limit: Int): List<SurveyMeasurement>

    /**
     * Each cell where [receiverNodeId] heard a sender directly since [sinceMs], across sessions on the same network
     * ([SAME_NETWORK]), with its latest direct time and strongest signal. Fixless receptions share a null-cell row.
     * Another network surveyed with the same radio adds no candidates. The `direct` WHERE clause repeats [isDirect],
     * with [relayByteOf]'s `00` → `FF` mapping as the CASE; the signal CASE repeats [signalOf] (aggregate MAX skips its
     * NULLs; two-argument MIN is the scalar one).
     */
    @Query(
        "WITH direct AS (SELECT m.sender_node_id, m.heard_at_ms, m.latitude, m.longitude, m.rx_rssi, m.rx_snr " +
            "FROM survey_measurement m JOIN survey_session s ON s.id = m.session_id " +
            "WHERE s.receiver_node_id = :receiverNodeId AND $SAME_NETWORK AND m.heard_at_ms >= :sinceMs " +
            "AND m.hop_start = m.hop_limit " +
            "AND m.relay_byte = CASE WHEN m.sender_node_id % 256 = 0 THEN 255 ELSE m.sender_node_id % 256 END) " +
            "SELECT d.sender_node_id AS nodeId, MAX(d.heard_at_ms) AS lastDirectMs, " +
            "CAST(ROUND(d.latitude * 500) AS INTEGER) AS latitudeCell, " +
            "CAST(ROUND(d.longitude * 500) AS INTEGER) AS longitudeCell, " +
            "MAX(CASE WHEN d.rx_rssi IS NULL OR d.rx_rssi = 0 THEN NULL " +
            "ELSE d.rx_rssi + MIN(COALESCE(d.rx_snr, 0), 0) END) AS strongestSignal " +
            "FROM direct d GROUP BY d.sender_node_id, latitudeCell, longitudeCell",
    )
    suspend fun directPlaces(
        receiverNodeId: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
        sinceMs: Long,
    ): List<DirectPlace>

    /**
     * The keys sessions that started before [startedAt] on this network ([SAME_NETWORK]) have in one cell.
     * [SurveyMeasurement.localNew] compares against them.
     */
    @Query(
        "SELECT DISTINCT m.relay_byte AS relayByte, CASE WHEN $IS_DIRECT THEN m.sender_node_id END AS directSender " +
            "FROM survey_measurement m JOIN survey_session s ON s.id = m.session_id " +
            "WHERE m.cell_x = :cellX AND m.cell_y = :cellY AND s.started_at < :startedAt AND $SAME_NETWORK",
    )
    suspend fun cellKeys(
        cellX: Int,
        cellY: Int,
        startedAt: Long,
        spreadingFactor: Int,
        bandwidthKhz: Float,
        frequencyMhz: Float?,
    ): List<CellKey>

    suspend fun cellKeys(session: SurveySession, cell: SurveyCell): List<CellKey> = with(session) {
        cellKeys(cell.x, cell.y, startedAt, spreadingFactor, bandwidthKhz, frequencyMhz)
    }

    /** [directPlaces] for [session]'s radio and settings. */
    suspend fun directPlaces(session: SurveySession, sinceMs: Long): List<DirectPlace> = with(session) {
        directPlaces(receiverNodeId, spreadingFactor, bandwidthKhz, frequencyMhz, sinceMs)
    }

    @Query("UPDATE survey_session SET status = :status, ended_at = :endedAt WHERE id = :sessionId")
    suspend fun endSession(sessionId: String, status: SurveyStatus, endedAt: Long)

    /** Marks sessions a dead process left recording; they end at their last measurement. */
    @Query(
        "UPDATE survey_session SET status = 'INTERRUPTED', ended_at = COALESCE(" +
            "(SELECT MAX(heard_at_ms) FROM survey_measurement WHERE session_id = survey_session.id), started_at) " +
            "WHERE status = 'RECORDING'",
    )
    suspend fun interruptRecording()

    @Query("SELECT id FROM survey_session WHERE status = 'RECORDING'")
    suspend fun recordingSessionIds(): List<String>

    /** [interruptRecording], and counts those sessions, whose end never did ([recountNews]). */
    @Transaction
    suspend fun recoverRecording() {
        val ids = recordingSessionIds()
        interruptRecording()
        ids.forEach { recountNews(it) }
    }

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

    @Query(
        "UPDATE survey_session SET uploaded_seq = 0, last_upload_at = NULL, last_upload_error = NULL, " +
            "server_kept = 0, server_skipped = 0, server_skip_reasons = NULL, " +
            "news_new_cells = NULL",
    )
    suspend fun resetSessionUploads()

    @Query("UPDATE survey_measurement SET server_new = NULL WHERE server_new IS NOT NULL")
    suspend fun clearServerNew()

    /** Forgets every session's upload progress and what SigSleuth said, which belong to the server they came from. */
    @Transaction
    suspend fun resetUploads() {
        resetSessionUploads()
        clearServerNew()
        clearDeleted()
    }

    @Query("UPDATE survey_measurement SET server_new = :flags WHERE session_id = :sessionId AND seq = :seq")
    suspend fun setServerNew(sessionId: String, seq: Long, flags: Int)

    /** Saves what SigSleuth said each of [sessionId]'s measurements adds ([SurveyMeasurement.serverNew]), by seq. */
    @Transaction
    suspend fun markNew(sessionId: String, flags: Map<Long, Int>) {
        flags.forEach { (seq, value) -> setServerNew(sessionId, seq, value) }
    }

    /**
     * [uploaded] and [markNew] for one batch, together: the next run starts after the batch, so flags saved apart from
     * the progress would be lost for good by a cancel between the two.
     */
    @Transaction
    @Suppress("LongParameterList")
    suspend fun batchUploaded(
        sessionId: String,
        uploadedSeq: Long,
        at: Long,
        kept: Long,
        skipped: Long,
        skipReasons: String?,
        flags: Map<Long, Int>,
    ) {
        uploaded(sessionId, uploadedSeq, at, kept, skipped, skipReasons)
        markNew(sessionId, flags)
    }

    @Query(NEWS)
    suspend fun countNews(sessionId: String): SurveyNews

    /** The distinct senders every session heard directly where it had a cell, as [SurveyNews.direct] counts them. */
    @Query("SELECT COUNT(DISTINCT sender_node_id) FROM survey_measurement WHERE cell_x IS NOT NULL AND $IS_DIRECT")
    suspend fun countDirectSenders(): Int

    /** [countDirectSenders], following the session table as [sessions] does, so not each insert while recording. */
    fun directSenders(): Flow<Int> = sessionRows().map { countDirectSenders() }

    @Query(
        "UPDATE survey_session SET news_cells = :cells, news_relays = :relays, news_direct = :direct, " +
            "news_new_cells = :newCells, news_local_new_cells = :localNewCells, " +
            "news_local_new_relay_cells = :localNewRelayCells, news_local_new_direct_cells = :localNewDirectCells " +
            "WHERE id = :sessionId",
    )
    @Suppress("LongParameterList")
    suspend fun saveNews(
        sessionId: String,
        cells: Int,
        relays: Int,
        direct: Int,
        newCells: Int?,
        localNewCells: Int,
        localNewRelayCells: Int,
        localNewDirectCells: Int,
    )

    /**
     * Counts [sessionId]'s [SurveyNews] and keeps it on the session, so the session list reads it rather than counting
     * every measurement again.
     */
    @Transaction
    suspend fun recountNews(sessionId: String): SurveyNews = countNews(sessionId).also {
        saveNews(
            sessionId,
            it.cells,
            it.relays,
            it.direct,
            it.newCells,
            it.localNewCells,
            it.localNewRelayCells,
            it.localNewDirectCells,
        )
    }

    @Query("UPDATE survey_session SET last_upload_error = :error WHERE id = :sessionId")
    suspend fun uploadFailed(sessionId: String, error: String)

    @Query("DELETE FROM survey_measurement WHERE session_id = :sessionId")
    suspend fun deleteMeasurements(sessionId: String)

    @Query("DELETE FROM survey_session WHERE id = :sessionId")
    suspend fun deleteSessionRow(sessionId: String)

    @Query("SELECT * FROM survey_session WHERE id = :sessionId")
    suspend fun session(sessionId: String): SurveySession?

    @Query(
        "SELECT DISTINCT cell_x AS x, cell_y AS y FROM survey_measurement " +
            "WHERE session_id = :sessionId AND cell_x IS NOT NULL",
    )
    suspend fun sessionCells(sessionId: String): List<SurveyCell>

    /** Every session's measurements in one cell, in start order. */
    @Query("$CELL_ROWS AND m.cell_x = :cellX AND m.cell_y = :cellY$START_ORDER")
    suspend fun cellRows(cellX: Int, cellY: Int): List<CellRow>

    @Query("UPDATE survey_measurement SET local_new = :bits WHERE seq = :seq")
    suspend fun setLocalNew(seq: Long, bits: Int)

    @Query("SELECT * FROM survey_deleted WHERE id = 0")
    fun deleted(): Flow<SurveyDeleted?>

    @Query("INSERT OR IGNORE INTO survey_deleted (id, new_cells) VALUES (0, 0)")
    suspend fun createDeleted()

    @Query("UPDATE survey_deleted SET new_cells = new_cells + :cells")
    suspend fun addDeleted(cells: Int)

    @Query("DELETE FROM survey_deleted")
    suspend fun clearDeleted()

    /**
     * Deletes a session. What it added to SigSleuth's map stays in the phone's total ([SurveyDeleted]); what it was
     * first on the phone to have passes to the later sessions that have it too ([recountAfter]).
     */
    @Transaction
    suspend fun deleteSession(sessionId: String) {
        val session = session(sessionId) ?: return
        val news = countNews(sessionId)
        news.newCells?.let { cells ->
            createDeleted()
            addDeleted(cells)
        }
        val cells = sessionCells(sessionId)
        deleteMeasurements(sessionId)
        deleteSessionRow(sessionId)
        recountAfter(session, cells)
    }

    /**
     * Sets [SurveyMeasurement.localNew] again ([withLocalNew]) for sessions that started after [deleted] on its
     * network, in its [cells], and recounts those that changed. Removing a session only makes keys newer, so nothing
     * else can change. Each cell is read once, every session's rows: a later session can match a network the deleted
     * one doesn't.
     */
    suspend fun recountAfter(deleted: SurveySession, cells: List<SurveyCell>) {
        val network = with(deleted) { SurveyNetwork(spreadingFactor, bandwidthKhz, frequencyMhz) }
        val changed = mutableSetOf<String>()
        for (cell in cells) {
            for ((row, bits) in cellRows(cell.x, cell.y).asSequence().withLocalNew()) {
                if (row.startedAt > deleted.startedAt && row.network.same(network) && bits != row.localNew) {
                    setLocalNew(row.seq, bits)
                    changed += row.sessionId
                }
            }
        }
        changed.forEach { recountNews(it) }
    }
}

/**
 * The survey's own database, `signal_survey.db`. It stays apart from the per-device app database, which is swapped when
 * the user picks another radio and migrated often upstream.
 */
@Database(
    entities = [SurveySession::class, SurveyMeasurement::class, SurveyDeleted::class],
    version = 6,
    exportSchema = true,
    // 2: what SigSleuth kept and skipped of each session. 3: each measurement's consent bit and default key. 4: whether
    // each session may be uploaded. 5: each measurement's cell, what SigSleuth said it adds and what it adds to the
    // phone's earlier sessions, filled in for older rows, each session's counts of them, and what deleted sessions had
    // added to the map. 6: those counts by cell (distinct relay bytes and direct senders, cells with a new one) rather
    // than by pairs of a cell and a key, and no relay or direct counts from SigSleuth or for deleted sessions.
    autoMigrations =
    [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5, spec = FillNewToYou::class),
        AutoMigration(from = 5, to = 6, spec = CountNewsByCell::class),
    ],
)
abstract class SurveyDatabase : RoomDatabase() {
    abstract fun surveyDao(): SurveyDao
}
