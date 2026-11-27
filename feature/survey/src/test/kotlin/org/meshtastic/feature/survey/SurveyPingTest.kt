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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SurveyPingTest {
    private val ping = SurveyPing(id = 77, sentAtMs = 5_000)

    /** A direct traceroute reply from [sender], heard at [at], having heard the survey node at [towards] dB. */
    private fun reply(sender: Long, towards: Float?, at: Long = 6_000) = SurveyMeasurement(
        sessionId = "s",
        heardAtMs = at,
        latitude = null,
        longitude = null,
        accuracyM = null,
        fixAgeMs = null,
        senderNodeId = sender,
        recipientNodeId = 1,
        packetId = sender,
        rxSnr = -5f,
        rxRssi = -100,
        hopStart = 0,
        hopLimit = 0,
        relayByte = null,
        snrTowards = towards,
    )

    @Test
    fun `an answer to this ping is listed with both SNRs`() {
        val answered = ping.withAnswer(reply(42, towards = -7.5f), requestId = 77)

        assertEquals(
            mapOf(42L to PingAnswer(42, heardSnr = -5f, heardRssi = -100, snrTowards = -7.5f)),
            answered.answers,
        )
    }

    @Test
    fun `a reply to another request is not an answer, whenever it was heard`() {
        // The app's own traceroute, answered after the ping, and an earlier ping's answer from before it.
        assertSame(ping, ping.withAnswer(reply(42, towards = -7.5f), requestId = 900))
        assertSame(ping, ping.withAnswer(reply(43, towards = 2f, at = 4_000), requestId = 76))
    }

    @Test
    fun `a packet without the SNR it was heard at is not an answer`() {
        assertSame(ping, ping.withAnswer(reply(42, towards = null), requestId = 77))
    }
}
