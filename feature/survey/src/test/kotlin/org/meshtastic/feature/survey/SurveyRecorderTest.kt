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

import co.touchlab.kermit.Severity
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
import org.meshtastic.core.testing.CapturingLogWriter
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

    /** Set to make the clock throw, which fails capturing a packet. */
    private var clockError: IllegalStateException? = null

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
                override fun wallTimeMs() = clockError?.let { throw it } ?: 1_000_000L

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

    private fun hear(from: Int = 42, generation: Long = 1) = tap.listener?.invoke(
        MeshPacket.Builder()
            .apply {
                this.from = from
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
    fun `a capture failure is logged once per session`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = recorder()
        val logs = CapturingLogWriter.install()
        try {
            fun warnings() = logs.messages(Severity.Warn).count { it.startsWith("Signal survey could not record") }

            assertTrue(recorder.start())
            clockError = IllegalStateException("capture bug")
            hear()
            hear()
            assertEquals(1, warnings())
            assertEquals(0, recorder.state.value.measurementCount)

            clockError = null
            recorder.stop()
            assertTrue(recorder.start())
            clockError = IllegalStateException("capture bug")
            hear()
            assertEquals(2, warnings())
        } finally {
            CapturingLogWriter.uninstall()
        }
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
