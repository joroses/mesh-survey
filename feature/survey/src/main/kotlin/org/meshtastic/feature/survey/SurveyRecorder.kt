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

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import org.meshtastic.core.common.BuildConfigProvider
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.common.util.safeCatching
import org.meshtastic.core.model.ChannelOption
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.model.RegionInfo
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.RadioSessionContext
import org.meshtastic.core.repository.ServiceRepository
import org.meshtastic.core.repository.SurveyPacketTap
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.MeshPacket
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import org.meshtastic.core.model.Channel as MeshChannel

/** Why the survey could not start, or why it stopped on its own. */
enum class SurveyProblem {
    NO_RADIO,
    NO_LORA_SETTINGS,
    NO_LOCATION,
    STORAGE,
}

/** What the screen and the notification show. */
data class SurveyState(
    val session: SurveySession? = null,
    val waitingForRadio: Boolean = false,
    val measurementCount: Int = 0,
    val fix: SurveyFix? = null,
    val lastMeasurement: SurveyMeasurement? = null,
    /** The distinct relay bytes this session's measurements carry; a packet heard directly carries its sender's. */
    val relays: Set<Int> = emptySet(),
    val problem: SurveyProblem? = null,
    /** How the last session ended, until the next one starts. */
    val lastEnd: SurveyStatus? = null,
)

/**
 * The LoRa settings a session is recorded under. A change ends the session, so one session never mixes presets (Local
 * Mesh Discovery switches the preset while it scans). Hop limit and transmit power do not matter here.
 */
data class SurveyLora(
    val modemPreset: String?,
    val spreadingFactor: Int,
    val bandwidthKhz: Float,
    val region: Int,
    val channel: Int,
    /** The frequency the app's LoRa settings show, in MHz; 0 when the node has no region or no primary channel. */
    val frequency: Float,
    val frequencyOffset: Float,
)

private const val MIN_SPREADING_FACTOR = 5
private const val MAX_SPREADING_FACTOR = 12

/** The session-relevant LoRa settings, or null while the node has not sent usable ones. */
fun surveyLora(config: LocalConfig, channels: ChannelSet): SurveyLora? {
    val lora = config.lora ?: return null
    val preset = ChannelOption.from(lora.modem_preset).takeIf { lora.use_preset }
    val spreadingFactor = if (lora.use_preset) preset?.spreadingFactor else lora.spread_factor
    return spreadingFactor
        ?.takeIf { it in MIN_SPREADING_FACTOR..MAX_SPREADING_FACTOR }
        ?.let {
            SurveyLora(
                modemPreset = preset?.modemPreset?.name,
                spreadingFactor = it,
                bandwidthKhz = lora.bandwidthKhz(preset),
                region = lora.region.value,
                channel = lora.channel_num,
                // Unless channel_num pins it, the slot follows the primary channel's name, so renaming it moves the
                // radio.
                frequency = channels.settings.firstOrNull()?.let { MeshChannel(it, lora).radioFreq } ?: 0f,
                frequencyOffset = lora.frequency_offset,
            )
        }
}

/**
 * The bandwidth the radio uses, in kHz. Repeats upstream's private `LoRaConfig.bandwidth(regionInfo)` in core:model's
 * `ChannelOption.kt`, so the fork changes no upstream file: wide-LoRa regions widen presets, and some custom codes are
 * rounded (62 means 62.5 kHz).
 */
@Suppress("MagicNumber") // The radio's bandwidth codes.
private fun Config.LoRaConfig.bandwidthKhz(preset: ChannelOption?): Float =
    if (preset != null) {
        val wide = RegionInfo.fromRegionCode(region)?.wideLora == true
        preset.bandwidth * 1000 * if (wide) 3.25f else 1f
    } else {
        when (bandwidth) {
            31 -> 31.25f
            62 -> 62.5f
            200 -> 203.125f
            400 -> 406.25f
            800 -> 812.5f
            1600 -> 1625f
            else -> bandwidth.toFloat()
        }
    }

/**
 * Records a signal survey: one session at a time, with every LoRa packet the survey node hears and the phone's newest
 * GPS fix. The session ends on Stop, when the survey node or its LoRa settings change, or when the radio stays away for
 * [RADIO_WAIT]. While the radio is away GPS is off, so a forgotten survey does not drain the battery.
 *
 * The packet tap runs on the inbound radio pipeline, so it only builds the row and hands it to a bounded channel; one
 * writer coroutine does the inserts. A full channel drops the packet rather than wait.
 */
@Suppress("LongParameterList", "TooManyFunctions")
@Single
class SurveyRecorder(
    private val tap: SurveyPacketTap,
    private val location: SurveyLocation,
    private val clock: SurveyClock,
    private val dao: SurveyDao,
    private val nodes: NodeRepository,
    private val radio: RadioInterfaceService,
    private val service: ServiceRepository,
    private val radioConfig: RadioConfigRepository,
    private val buildConfig: BuildConfigProvider,
    private val scope: ApplicationCoroutineScope,
) {
    private val mutableState = MutableStateFlow(SurveyState())
    val state: StateFlow<SurveyState> = mutableState.asStateFlow()

    /** What the packet tap needs, swapped as one object so the radio thread never sees half of it. */
    private class Active(
        val session: SurveySession,
        val surveyNode: Int,
        val address: String,
        val rows: Channel<SurveyMeasurement>,
    ) {
        /**
         * The transport session whose node and LoRa settings the watcher has checked against this session; only its
         * packets are recorded. Null while the radio is away, and a reconnect is a new transport session, so the
         * packets it delivers during the handshake wait until the watcher has seen the handshake finish with the same
         * settings.
         */
        @Volatile var verified: RadioSessionContext? = null
    }

    @Volatile private var active: Active? = null

    @Volatile private var fix: SurveyFix? = null
    private val lifecycle = Mutex()
    private var writer: Job? = null
    private var watcher: Job? = null

    // A session left recording belongs to a process that died; mark it before any new session starts.
    private val recovery = scope.launch { safeCatching { dao.interruptRecording() } }

    init {
        // "Connect a radio first" is out of date as soon as one connects.
        scope.launch {
            service.connectionState.collect { connection ->
                if (connection == ConnectionState.Connected) {
                    mutableState.update { if (it.problem == SurveyProblem.NO_RADIO) it.copy(problem = null) else it }
                }
            }
        }
    }

    @Suppress("ReturnCount")
    suspend fun start(): Boolean = lifecycle.withLock {
        recovery.join()
        if (active != null) return true
        val myNode = nodes.myNodeInfo.value
        val address = radio.getDeviceAddress()
        if (myNode == null || address.isNullOrBlank() || service.connectionState.value != ConnectionState.Connected) {
            return fail(SurveyProblem.NO_RADIO)
        }
        val lora = currentLora() ?: return fail(SurveyProblem.NO_LORA_SETTINGS)
        if (!location.isAvailable()) return fail(SurveyProblem.NO_LOCATION)
        val session =
            SurveySession(
                id = Uuid.random().toString(),
                startedAt = clock.wallTimeMs(),
                receiverNodeId = myNode.myNodeNum.toUInt().toLong(),
                receiverName = nodes.ourNodeInfo.value?.user?.long_name.orEmpty(),
                modemPreset = lora.modemPreset,
                spreadingFactor = lora.spreadingFactor,
                bandwidthKhz = lora.bandwidthKhz,
                frequencyMhz = lora.frequency.takeIf { it > 0f },
                appVersion = buildConfig.versionName,
            )
        if (safeCatching { dao.insertSession(session) }.isFailure) return fail(SurveyProblem.STORAGE)
        val rows = Channel<SurveyMeasurement>(capacity = QUEUE_CAPACITY)
        val current = Active(session, myNode.myNodeNum, address, rows)
        mutableState.value = SurveyState(session = session)
        writer = scope.launch { write(rows) }
        watcher = scope.launch { watch(current, lora) }
        active = current
        tap.listener = ::onPacket
        resumeGps()
        Logger.i { "Signal survey started" }
        true
    }

    /** Ends the recording session. [sessionId], when given, keeps a late automatic stop off a newer session. */
    suspend fun stop(status: SurveyStatus = SurveyStatus.STOPPED, sessionId: String? = null) = lifecycle.withLock {
        val current = active ?: return@withLock
        if (sessionId != null && sessionId != current.session.id) return@withLock
        active = null
        tap.listener = null
        // The watcher may be resuming GPS on another thread right now; let it finish before GPS goes off for good.
        watcher?.cancelAndJoin()
        pauseGps()
        current.rows.close()
        writer?.join() // Saves the rows already queued.
        safeCatching { dao.endSession(current.session.id, status, clock.wallTimeMs()) }
        // A problem met while recording belonged to this session; the idle card shows only how it ended.
        mutableState.update {
            it.copy(session = null, waitingForRadio = false, fix = null, problem = null, lastEnd = status)
        }
        Logger.i { "Signal survey ended: $status" }
    }

    /** Shows a problem found before [start] could run, such as a refused permission. */
    fun report(problem: SurveyProblem) {
        mutableState.update { it.copy(problem = problem) }
    }

    private fun fail(problem: SurveyProblem): Boolean {
        report(problem)
        return false
    }

    private fun onPacket(packet: MeshPacket, session: RadioSessionContext) {
        val current = active?.takeIf { it.verified == session } ?: return
        val row =
            measurementOf(
                packet,
                current.surveyNode,
                current.session.id,
                clock.wallTimeMs(),
                clock.elapsedRealtimeMs(),
                fix,
            )
        if (row != null && current.rows.trySend(row).isFailure) {
            Logger.w { "Signal survey queue full; dropped a packet" }
        }
    }

    private suspend fun write(rows: Channel<SurveyMeasurement>) {
        for (row in rows) {
            val seq = safeCatching { dao.insertMeasurement(row) }.getOrNull()
            if (seq == null) {
                mutableState.update { it.copy(problem = SurveyProblem.STORAGE) }
                continue
            }
            mutableState.update {
                it.copy(
                    measurementCount = it.measurementCount + 1,
                    lastMeasurement = row.copy(seq = seq),
                    relays = row.relayByte?.let { byte -> it.relays + byte } ?: it.relays,
                )
            }
        }
    }

    private suspend fun currentLora(): SurveyLora? =
        surveyLora(radioConfig.localConfigFlow.first(), radioConfig.channelSetFlow.first())

    /** What the watcher acts on: why the session must end, whether the radio is here, and which transport it uses. */
    private data class RadioCheck(val end: SurveyStatus?, val connected: Boolean, val transport: RadioSessionContext?)

    /**
     * Ends the session when the survey node or its LoRa settings change; pauses GPS while the radio is away. Capture
     * follows the connection: it stops when the radio leaves and resumes, for the new transport session, once the
     * handshake has finished and the node and settings still match.
     */
    private suspend fun watch(current: Active, lora: SurveyLora) = coroutineScope {
        val sessionId = current.session.id
        var radioWait: Job? = null
        var loraSettle: Job? = null
        combine(
            service.connectionState,
            nodes.myNodeInfo,
            radio.activeSession,
            radio.currentDeviceAddressFlow,
            combine(radioConfig.localConfigFlow, radioConfig.channelSetFlow, ::surveyLora),
        ) { connection, myNode, transport, address, loraNow ->
            val connected = connection == ConnectionState.Connected
            val end =
                when {
                    address != current.address -> SurveyStatus.NODE_CHANGED
                    myNode != null && myNode.myNodeNum != current.surveyNode -> SurveyStatus.NODE_CHANGED
                    connected && loraNow != null && loraNow != lora -> SurveyStatus.LORA_CHANGED
                    else -> null
                }
            RadioCheck(end, connected, transport?.takeIf { it.address == current.address })
        }
            .distinctUntilChanged()
            .collect { (end, connected, transport) ->
                current.verified = null
                loraSettle?.cancel()
                loraSettle = null
                when {
                    end != null && end != SurveyStatus.LORA_CHANGED -> scope.launch { stop(end, sessionId) }

                    connected -> {
                        radioWait?.cancel()
                        radioWait = null
                        resumeGps()
                        if (end == SurveyStatus.LORA_CHANGED) {
                            // During a reconnect the config and channel flows can show a half-written state for a
                            // moment, so only a mismatch that lasts ends the session. Nothing is recorded meanwhile.
                            loraSettle = launch {
                                delay(LORA_SETTLE)
                                val now = currentLora()
                                when {
                                    now != null && now != lora ->
                                        scope.launch { stop(SurveyStatus.LORA_CHANGED, sessionId) }

                                    now == lora && transport != null -> current.verified = transport
                                }
                            }
                        } else if (transport != null && currentLora() == lora) {
                            // Read the settings again rather than trust this emission: a setting saved during the
                            // handshake may not have reached the combined flow yet. A mismatch arrives there next,
                            // so until then nothing is recorded.
                            current.verified = transport
                        }
                    }

                    radioWait == null -> {
                        pauseGps()
                        radioWait = launch {
                            delay(RADIO_WAIT)
                            scope.launch { stop(SurveyStatus.RADIO_LOST, sessionId) }
                        }
                    }
                }
            }
    }

    private fun resumeGps() {
        mutableState.update { it.copy(waitingForRadio = false) }
        val started = safeCatching {
            location.start { newFix ->
                fix = newFix
                mutableState.update { it.copy(fix = newFix) }
            }
        }
        // Location turned off or revoked mid-session: keep recording, the rows just carry no position.
        if (started.isFailure) mutableState.update { it.copy(problem = SurveyProblem.NO_LOCATION) }
    }

    private fun pauseGps() {
        location.stop()
        fix = null
        mutableState.update { it.copy(waitingForRadio = active != null, fix = null) }
    }

    companion object {
        /** How long a session waits for its radio to reconnect before it ends. */
        val RADIO_WAIT = 30.minutes

        /** How long LoRa settings must differ from the session's before it ends. */
        val LORA_SETTLE = 10.seconds
        private const val QUEUE_CAPACITY = 256
    }
}
