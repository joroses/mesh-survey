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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.meshtastic.core.common.di.ApplicationCoroutineScope
import org.meshtastic.core.model.ConnectionState
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioSessionContext
import org.meshtastic.core.repository.SurveyPacketTap
import org.meshtastic.core.testing.FakeBuildConfigProvider
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.core.testing.FakeRadioInterfaceService
import org.meshtastic.core.testing.FakeServiceRepository
import org.meshtastic.core.testing.TestDataFactory
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.ChannelSettings
import org.meshtastic.proto.Config
import org.meshtastic.proto.LocalConfig
import org.meshtastic.proto.MeshPacket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SurveyRecorderTest {
    private val tap =
        object : SurveyPacketTap {
            override var listener: ((MeshPacket, RadioSessionContext) -> Unit)? = null
        }
    private val location = FakeSurveyLocation()
    private val dao = FakeSurveyDao()
    private val nodes = FakeNodeRepository().apply { setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 1)) }
    private val service = FakeServiceRepository().apply { setConnectionState(ConnectionState.Connected) }
    private val lora = MutableStateFlow(loraConfig(Config.LoRaConfig.ModemPreset.LONG_FAST))
    private val channels =
        FakeRadioConfigRepository().apply {
            setChannelSet(
                ChannelSet.Builder()
                    .apply {
                        settings = listOf(ChannelSettings.Builder().build())
                        lora_config = loraConfig(Config.LoRaConfig.ModemPreset.LONG_FAST)
                    }
                    .build(),
            )
        }
    private val radioConfig =
        object : RadioConfigRepository by channels {
            override val localConfigFlow: Flow<LocalConfig> = lora.map {
                LocalConfig.Builder().apply { lora = it }.build()
            }
        }

    private lateinit var radio: FakeRadioInterfaceService

    private fun loraConfig(
        preset: Config.LoRaConfig.ModemPreset,
        region: Config.LoRaConfig.RegionCode = Config.LoRaConfig.RegionCode.EU_868,
    ) = Config.LoRaConfig.Builder()
        .apply {
            use_preset = true
            modem_preset = preset
            this.region = region
        }
        .build()

    private fun primaryChannel(name: String) = ChannelSet.Builder()
        .apply { settings = listOf(ChannelSettings.Builder().apply { this.name = name }.build()) }
        .build()

    private fun TestScope.recorder(): SurveyRecorder {
        radio =
            FakeRadioInterfaceService(backgroundScope).apply {
                setDeviceAddress("xradio")
                connect()
            }
        val clock =
            object : SurveyClock {
                override fun wallTimeMs() = 1_000_000L

                override fun elapsedRealtimeMs() = 5_000L
            }
        return SurveyRecorder(
            tap,
            location,
            clock,
            dao,
            nodes,
            radio,
            service,
            radioConfig,
            FakeBuildConfigProvider(versionName = "2.8.3-survey"),
            object : ApplicationCoroutineScope, CoroutineScope by backgroundScope {},
        )
    }

    private fun hear(from: Int = 42, generation: Long = 1, hopsUsed: Int = 0, relay: Int = 0) = tap.listener?.invoke(
        MeshPacket.Builder()
            .apply {
                this.from = from
                hop_start = HOP_START
                hop_limit = HOP_START - hopsUsed
                relay_node = relay
                id = 7
                transport_mechanism = MeshPacket.TransportMechanism.TRANSPORT_LORA
            }
            .build(),
        RadioSessionContext(generation = generation, address = "xradio"),
    )

    @Test
    fun `records heard packets, and a change of LoRa settings ends the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = recorder()

            assertTrue(recorder.start())
            val session = dao.sessions.single()
            assertEquals("LONG_FAST", session.modemPreset)
            assertEquals(11, session.spreadingFactor)
            assertEquals(250f, session.bandwidthKhz)
            assertEquals(869.525f, session.frequencyMhz)
            hear()
            assertEquals(1, recorder.state.value.measurementCount)

            lora.value = loraConfig(Config.LoRaConfig.ModemPreset.MEDIUM_FAST)
            hear()
            assertEquals(SurveyStatus.RECORDING, dao.sessions.single().status) // It waits for the change to last.
            advanceTimeBy(SurveyRecorder.LORA_SETTLE + 1.seconds)

            assertNull(recorder.state.value.session)
            assertEquals(SurveyStatus.LORA_CHANGED, dao.sessions.single().status)
            assertNull(tap.listener)
            assertFalse(location.running)
            assertEquals(1, dao.measurements.size)
        }

    @Test
    fun `custom settings give the bandwidth the radio uses`() {
        val custom =
            Config.LoRaConfig.Builder()
                .apply {
                    use_preset = false
                    spread_factor = 7
                    bandwidth = 62
                    region = Config.LoRaConfig.RegionCode.EU_868
                }
                .build()

        val config = LocalConfig.Builder().apply { lora = custom }.build()

        assertEquals(62.5f, surveyLora(config, primaryChannel(""))?.bandwidthKhz)
    }

    @Test
    fun `a new frequency offset ends the session`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder()
        recorder.start()

        lora.value = lora.value.newBuilder().apply { frequency_offset = 0.01f }.build()
        advanceTimeBy(SurveyRecorder.LORA_SETTLE + 1.seconds)

        assertEquals(SurveyStatus.LORA_CHANGED, dao.sessions.single().status)
        assertNull(recorder.state.value.session)
    }

    @Test
    fun `renaming the primary channel moves the frequency and ends the session`() =
        runTest(UnconfinedTestDispatcher()) {
            // US has many slots, and the primary channel's name picks one of them.
            lora.value = loraConfig(Config.LoRaConfig.ModemPreset.LONG_FAST, Config.LoRaConfig.RegionCode.US)
            channels.setChannelSet(primaryChannel(""))
            val recorder = recorder()
            recorder.start()
            val frequency = dao.sessions.single().frequencyMhz

            channels.setChannelSet(primaryChannel("Hike")) // Slot 7, where the default LongFast is in slot 20.
            advanceTimeBy(SurveyRecorder.LORA_SETTLE + 1.seconds)

            assertEquals(906.875f, frequency)
            assertEquals(SurveyStatus.LORA_CHANGED, dao.sessions.single().status)
        }

    @Test
    fun `packets from a reconnect wait until the handshake has finished`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder()
        recorder.start()
        hear()

        service.setConnectionState(ConnectionState.Disconnected)
        hear()
        radio.restartTransport()
        service.setConnectionState(ConnectionState.Connecting)
        hear(generation = 2) // The new transport, before its handshake has confirmed the node and settings.
        assertEquals(1, recorder.state.value.measurementCount)

        service.setConnectionState(ConnectionState.Connected)
        hear(generation = 1) // The old transport is gone.
        hear(generation = 2)

        assertEquals(2, recorder.state.value.measurementCount)
        assertEquals(SurveyStatus.RECORDING, dao.sessions.single().status)
    }

    @Test
    fun `settings that differ only for a moment during a reconnect keep the session`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = recorder()
            recorder.start()
            val settings = channels.channelSetFlow.first()

            service.setConnectionState(ConnectionState.Disconnected)
            radio.restartTransport()
            service.setConnectionState(ConnectionState.Connected)
            channels.setChannelSet(primaryChannel("Hike")) // The handshake's channels, not yet the node's.
            advanceTimeBy(SurveyRecorder.LORA_SETTLE / 2)
            channels.setChannelSet(settings)
            hear(generation = 2)
            advanceTimeBy(SurveyRecorder.LORA_SETTLE * 2)

            assertEquals(SurveyStatus.RECORDING, dao.sessions.single().status)
            assertEquals(1, recorder.state.value.measurementCount)
        }

    @Test
    fun `a lost radio pauses GPS until it returns, and ends the session if it stays away`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = recorder()
            recorder.start()

            service.setConnectionState(ConnectionState.Disconnected)
            assertTrue(recorder.state.value.waitingForRadio)
            assertFalse(location.running)
            service.setConnectionState(ConnectionState.Connected)
            assertTrue(location.running)

            service.setConnectionState(ConnectionState.Disconnected)
            advanceTimeBy(SurveyRecorder.RADIO_WAIT - 1.seconds)
            assertEquals(SurveyStatus.RECORDING, dao.sessions.single().status)
            advanceTimeBy(2.seconds)
            assertEquals(SurveyStatus.RADIO_LOST, dao.sessions.single().status)
            assertNull(recorder.state.value.session)
        }

    @Test
    fun `another survey node ends the session`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder()
        recorder.start()

        nodes.setMyNodeInfo(TestDataFactory.createMyNodeInfo(myNodeNum = 2))

        assertEquals(SurveyStatus.NODE_CHANGED, dao.sessions.single().status)
    }

    @Test
    fun `it does not start without a connected radio`() = runTest(UnconfinedTestDispatcher()) {
        service.setConnectionState(ConnectionState.Disconnected)
        val recorder = recorder()

        assertFalse(recorder.start())
        assertEquals(SurveyProblem.NO_RADIO, recorder.state.value.problem)
        assertNull(tap.listener)
        assertEquals(emptyList(), dao.sessions)
    }

    @Test
    fun `the no-radio problem clears once a radio connects`() = runTest(UnconfinedTestDispatcher()) {
        service.setConnectionState(ConnectionState.Disconnected)
        val recorder = recorder()
        recorder.start()

        service.setConnectionState(ConnectionState.Connected)

        assertNull(recorder.state.value.problem)
    }

    @Test
    fun `a direct packet names its sender, and makes it the likely relay of the next with its byte`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = recorder()
            recorder.start()
            val relays = { recorder.state.value.relays }

            hear(from = 0x13F, relay = 0x3F)
            assertEquals(RelayStats(1, 1_000_000, RelayGuess.Direct(0x13F)), relays()[0x3F])
            assertEquals(setOf(0x13FL), recorder.state.value.neighbours.keys)

            hear(from = 0x500, hopsUsed = 1, relay = 0x3F)
            assertEquals(RelayStats(2, 1_000_000, RelayGuess.Likely(0x13F)), relays()[0x3F])

            hear(from = 0x500, hopsUsed = 1, relay = 0x77)
            assertEquals(RelayGuess.Unknown, relays()[0x77]?.guess)

            hear(from = 0x500) // Older firmware: no relay byte.
            assertEquals(setOf(0x3F, 0x77), relays().keys)
            assertEquals(4, recorder.state.value.measurementCount)
        }

    @Test
    fun `an earlier session with the same radio seeds the guesses`() = runTest(UnconfinedTestDispatcher()) {
        val earlier = "earlier"
        dao.insertSession(
            SurveySession(
                id = earlier,
                startedAt = 0,
                status = SurveyStatus.STOPPED,
                receiverNodeId = 1,
                receiverName = "",
                modemPreset = "LONG_FAST",
                spreadingFactor = 11,
                bandwidthKhz = 250f,
                frequencyMhz = null,
                appVersion = "test",
            ),
        )
        dao.insertMeasurement(
            SurveyMeasurement(
                sessionId = earlier,
                heardAtMs = 999_000,
                latitude = null,
                longitude = null,
                accuracyM = null,
                fixAgeMs = null,
                senderNodeId = 0x13F,
                recipientNodeId = 0xFFFFFFFF,
                packetId = 1,
                rxSnr = null,
                rxRssi = null,
                hopStart = HOP_START,
                hopLimit = HOP_START,
                relayByte = 0x3F,
            ),
        )
        val recorder = recorder()
        recorder.start()

        hear(from = 0x500, hopsUsed = 1, relay = 0x3F)

        assertEquals(RelayGuess.Likely(0x13F), recorder.state.value.relays[0x3F]?.guess)
        assertEquals(setOf(0x13FL), recorder.state.value.neighbours.keys)
    }

    @Test
    fun `a problem met while recording ends with the session`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder()
        recorder.start()
        dao.failInserts = true
        hear()
        assertEquals(SurveyProblem.STORAGE, recorder.state.value.problem)

        recorder.stop()

        assertNull(recorder.state.value.problem)
        assertEquals(SurveyStatus.STOPPED, recorder.state.value.lastEnd)
    }
}

private const val HOP_START = 3

private class FakeSurveyLocation : SurveyLocation {
    var running = false

    override fun isAvailable() = true

    override fun start(onFix: (SurveyFix) -> Unit) {
        running = true
    }

    override fun stop() {
        running = false
    }
}
