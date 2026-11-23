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
@file:Suppress("MagicNumber")

package org.meshtastic.feature.survey.ui

import org.meshtastic.feature.survey.ReplayedRelays
import org.meshtastic.feature.survey.SurveyDeleted
import org.meshtastic.feature.survey.SurveyNews
import org.meshtastic.feature.survey.SurveyProblem
import org.meshtastic.feature.survey.SurveySession
import org.meshtastic.feature.survey.SurveySessionSummary
import org.meshtastic.feature.survey.SurveyState
import org.meshtastic.feature.survey.SurveyStatus
import org.meshtastic.feature.survey.SurveyUploadSettings
import org.meshtastic.feature.survey.SurveyUploader

/** A session of the survey radio, started [startedAgo] before [SNAPSHOT_NOW]; recording while [length] is null. */
@Suppress("LongParameterList")
internal fun session(
    id: String,
    startedAgo: Long,
    length: Long? = null,
    status: SurveyStatus = if (length == null) SurveyStatus.RECORDING else SurveyStatus.STOPPED,
    receiverName: String = "Roof",
    receiverNodeId: Long = RADIO_ID,
    preset: String? = "LONG_FAST",
    spreadingFactor: Int = 11,
    bandwidthKhz: Float = 250f,
    frequencyMhz: Float? = 869.525f,
    upload: Boolean = true,
    lastUploadAgo: Long? = null,
    lastUploadError: String? = null,
    serverKept: Long = 0,
    serverSkipped: Long = 0,
    news: SurveyNews? = null,
) = SurveySession(
    id = id,
    startedAt = ago(startedAgo),
    endedAt = length?.let { ago(startedAgo) + it },
    status = status,
    receiverNodeId = receiverNodeId,
    receiverName = receiverName,
    modemPreset = preset,
    spreadingFactor = spreadingFactor,
    bandwidthKhz = bandwidthKhz,
    frequencyMhz = frequencyMhz,
    appVersion = "screenshot",
    uploadedSeq = serverKept + serverSkipped,
    lastUploadAt = lastUploadAgo?.let(::ago),
    lastUploadError = lastUploadError,
    serverKept = serverKept,
    serverSkipped = serverSkipped,
    upload = upload,
    news = news,
)

internal fun SurveySession.summary(packets: Int, uploaded: Int = 0, relays: Int = news?.relays ?: 0) =
    SurveySessionSummary(
        this,
        measurementCount = packets,
        uploadedCount = uploaded,
        lastSeq = packets.toLong(),
        relayCount = relays,
    )

/**
 * The survey screen's states, from made-up data at [SNAPSHOT_NOW]. Each is one [SurveyUi]; the busy ones come from
 * [SyntheticMesh], the rest are written out.
 */
internal object SurveyScenarios {
    val server = SurveyUploadSettings(url = "https://sigsleuth.com", password = "made-up")
    private val manualServer = server.copy(automatic = false)

    // ---- Before a session ----

    /** Nothing set up yet: no radio, no server, no sessions. */
    val firstRun = SurveyUi(uploadSettings = SurveyUploadSettings())

    /** A radio and a server, but no sessions yet. */
    val ready = SurveyUi(radioConnected = true, uploadSettings = server)

    /** Past sessions of every kind, all uploaded or private but two, with lifetime totals. */
    val history =
        SurveyUi(
            radioConnected = true,
            uploadSettings = server,
            sessions = pastSessions(),
            deleted = SurveyDeleted(newCells = 41),
            directSenders = 57,
        )

    /** The last session lost its radio, location is refused, uploads are manual and two sessions wait. */
    val trouble =
        history.copy(
            radioConnected = false,
            state = SurveyState(lastEnd = SurveyStatus.RADIO_LOST, problem = SurveyProblem.NO_LOCATION),
            uploadSettings = manualServer,
        )

    /** The waiting sessions uploading. */
    val uploading = history.copy(uploading = true)

    // ---- Recording ----

    /** Started 20 s ago, nothing heard yet. */
    val justStarted =
        recording(session("start", startedAgo = 20 * SECOND), packets = 0) { state ->
            state.copy(fix = fixAged(SECOND))
        }

    private val quietMesh by lazy {
        SyntheticMesh(seed = 3, nodeCount = 6, packetCount = 14, length = 25 * MINUTE, sessionId = "quiet")
    }

    /** A quiet country road: a handful of packets from a few relays. */
    val quiet by lazy { meshRecording(quietMesh, newToYou = SurveyNews(cells = 9, relays = 4, direct = 1)) }

    private val busyMesh by lazy {
        SyntheticMesh(seed = 7, nodeCount = 140, packetCount = 2_400, length = 130 * MINUTE, sessionId = "busy")
    }

    /** A city mesh: thousands of packets, dozens of relay bytes, guesses of every kind and SigSleuth's answers. */
    val busy by lazy {
        meshRecording(
            busyMesh,
            newToYou =
            SurveyNews(
                cells = 318,
                relays = 97,
                direct = 41,
                localNewCells = 74,
                localNewRelays = 26,
                localNewDirect = 11,
            ),
            news = SurveyNews(cells = 301, relays = 92, direct = 39, newCells = 23),
        )
    }

    /** [busy] with its three busiest relay rows opened. */
    val busyOpenRows by lazy { busyMesh.busiest(3) }

    /** A long drive through new ground: every figure in the thousands. */
    val lotsOfNews by lazy {
        meshRecording(
            busyMesh,
            newToYou =
            SurveyNews(
                cells = 12_408,
                relays = 1_187,
                direct = 264,
                localNewCells = 9_872,
                localNewRelays = 142,
                localNewDirect = 198,
            ),
            news = SurveyNews(cells = 12_030, relays = 1_150, direct = 260, newCells = 8_745),
            packets = 48_906,
        )
    }

    /** The radio went away three minutes ago; the session waits for it. */
    val waitingForRadio by lazy {
        meshRecording(quietMesh, newToYou = SurveyNews(cells = 9, relays = 4, direct = 1)).let { ui ->
            ui.copy(state = ui.state.copy(waitingForRadio = true, fix = null))
        }
    }

    /** GPS lost for over a minute and poor: the last packet, a while ago, could not be placed. */
    val noGps by lazy {
        meshRecording(quietMesh, newToYou = SurveyNews(cells = 9, relays = 4, direct = 1)).let { ui ->
            val last =
                ui.state.lastMeasurement?.copy(
                    heardAtMs = ago(3 * MINUTE),
                    latitude = null,
                    longitude = null,
                    accuracyM = null,
                    fixAgeMs = null,
                    cellX = null,
                    cellY = null,
                )
            ui.copy(state = ui.state.copy(fix = fixAged(95 * SECOND, accuracyM = 60f), lastMeasurement = last))
        }
    }

    /** The server refuses the password, so packets wait; location was refused too. */
    val uploadFailed by lazy {
        val failing =
            session("busy", startedAgo = 130 * MINUTE, lastUploadAgo = 4 * MINUTE, lastUploadError = "HTTP 401")
        meshRecording(busyMesh, session = failing, uploaded = 1_100).let { ui ->
            ui.copy(state = ui.state.copy(uploadFailing = true, problem = SurveyProblem.NO_LOCATION))
        }
    }

    /** [busy] in car mode. */
    val carBusy by lazy { busy.copy(carMode = true) }

    /** [waitingForRadio] in car mode. */
    val carWaiting by lazy { waitingForRadio.copy(carMode = true) }

    // ---- Sheets ----

    /** The busy session after it ended, its relays rebuilt, opened from its card. */
    val endedRelays by lazy {
        OpenedSession(
            session("busy", startedAgo = 3 * HOUR, length = 130 * MINUTE),
            ReplayedRelays(busyMesh.relays, busyMesh.neighbours),
            busyMesh.names,
        )
    }

    /** An ended session whose relays are still being rebuilt. */
    val endedRelaysLoading = OpenedSession(session("quiet", startedAgo = 5 * HOUR, length = 25 * MINUTE))

    /** The recording session's upload sheet, with no connection for its 320 waiting packets. */
    val uploadSheetRecording =
        session(
            "busy",
            startedAgo = 130 * MINUTE,
            lastUploadAgo = 6 * MINUTE,
            lastUploadError = SurveyUploader.NO_CONNECTION,
        )
            .summary(packets = 2_400, uploaded = 2_080)

    // ---- Building blocks ----

    private fun recording(
        session: SurveySession,
        packets: Int,
        uploaded: Int = packets,
        state: (SurveyState) -> SurveyState,
    ) = SurveyUi(
        state = state(SurveyState(session = session, measurementCount = packets)),
        radioConnected = true,
        uploadSettings = server,
        sessions = listOf(session.summary(packets, uploaded)) + pastSessions(),
    )

    private fun meshRecording(
        mesh: SyntheticMesh,
        newToYou: SurveyNews? = null,
        news: SurveyNews? = null,
        packets: Int = mesh.packetCount,
        session: SurveySession = session(mesh.last?.sessionId ?: "mesh", startedAgo = 130 * MINUTE),
        uploaded: Int = packets,
    ) = recording(session, packets, uploaded) { state ->
        state.copy(
            directCount = mesh.directCount,
            acknowledged = uploaded.toLong(),
            news = news,
            newToYou = newToYou,
            fix = fixAged(2 * SECOND),
            lastMeasurement = mesh.last,
            relays = mesh.relays,
            neighbours = mesh.neighbours,
            mapped = mesh.mapped,
        )
    }
        .copy(relayNames = mesh.names)

    /** Ended sessions, newest first. */
    private fun pastSessions(): List<SurveySessionSummary> = listOf(
        session(
            "uploaded",
            startedAgo = 4 * HOUR,
            length = 100 * MINUTE,
            lastUploadAgo = 2 * HOUR,
            serverKept = 1_490,
            serverSkipped = 42,
            news =
            SurveyNews(
                cells = 212,
                relays = 48,
                direct = 19,
                newCells = 37,
                localNewCells = 64,
                localNewRelays = 22,
                localNewDirect = 9,
            ),
        )
            .summary(packets = 1_532, uploaded = 1_532),
        session(
            "refused",
            startedAgo = DAY + 2 * HOUR,
            length = 40 * MINUTE,
            status = SurveyStatus.RADIO_LOST,
            lastUploadAgo = DAY,
            lastUploadError = "HTTP 401",
            serverKept = 400,
            news = SurveyNews(cells = 61, relays = 17, direct = 6, localNewCells = 12),
        )
            .summary(packets = 640, uploaded = 400),
        session(
            "private",
            startedAgo = 2 * DAY,
            length = 12 * MINUTE,
            upload = false,
            news = SurveyNews(cells = 8, relays = 3, direct = 1, localNewCells = 2),
        )
            .summary(packets = 88),
        session(
            "offline",
            startedAgo = 3 * DAY,
            length = 55 * MINUTE,
            lastUploadAgo = 3 * DAY,
            lastUploadError = SurveyUploader.NO_CONNECTION,
            news = SurveyNews(cells = 97, relays = 21, direct = 7, localNewCells = 30),
        )
            .summary(packets = 911),
        session(
            "car",
            startedAgo = 6 * DAY,
            length = 5 * HOUR,
            status = SurveyStatus.LORA_CHANGED,
            receiverName = "Car",
            receiverNodeId = 0x5ac0ffeeL,
            preset = "MEDIUM_FAST",
            spreadingFactor = 9,
            lastUploadAgo = 6 * DAY,
            serverKept = 12_345,
            news = SurveyNews(cells = 2_210, relays = 133, direct = 58, newCells = 402, localNewCells = 1_870),
        )
            .summary(packets = 12_345, uploaded = 12_345),
        session("empty", startedAgo = 7 * DAY, length = 2 * MINUTE, status = SurveyStatus.INTERRUPTED)
            .summary(packets = 0),
        session(
            "custom",
            startedAgo = 9 * DAY,
            length = 30 * MINUTE,
            status = SurveyStatus.NODE_CHANGED,
            preset = null,
            spreadingFactor = 12,
            bandwidthKhz = 125f,
            frequencyMhz = 868.1f,
            lastUploadAgo = 9 * DAY,
            serverKept = 230,
            news = SurveyNews(cells = 40, relays = 9, direct = 4, newCells = 0, localNewCells = 5),
        )
            .summary(packets = 230, uploaded = 230),
    )
}
