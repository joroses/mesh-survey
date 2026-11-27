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
import kotlin.test.assertNull

class SurveyGpsAlertTest {
    private val alert = SurveyGpsAlert()
    private val goodFix = SurveyFix(elapsedRealtimeMs = 0, latitude = 41.9, longitude = 1.57, accuracyM = 14f)
    private val poorFix = goodFix.copy(accuracyM = 400f)

    /** A packet heard [atS] seconds in, placed on the map when [placed]. */
    private fun heard(atS: Int, placed: Boolean = false) = alert.measured(
        SurveyMeasurement(
            sessionId = "s",
            heardAtMs = atS * 1_000L,
            latitude = null,
            longitude = null,
            accuracyM = null,
            fixAgeMs = null,
            senderNodeId = 1,
            recipientNodeId = 2,
            packetId = atS.toLong(),
            rxSnr = -5f,
            rxRssi = -100,
            hopStart = 3,
            hopLimit = 3,
            relayByte = null,
            cellX = if (placed) 1 else null,
            cellY = if (placed) 1 else null,
        ),
    )

    @Test
    fun `sounds once packets have gone unplaced for half a minute, and goes with a good fix`() {
        assertNull(heard(0))
        assertNull(heard(29))
        assertEquals(GpsAlertChange.SOUND, heard(30))
        assertNull(heard(40))
        assertNull(alert.fixed(poorFix))
        assertEquals(GpsAlertChange.REMOVE, alert.fixed(goodFix))
        assertNull(alert.fixed(goodFix))
    }

    @Test
    fun `one packet after a long silence is not enough`() {
        assertNull(heard(0, placed = true))
        assertNull(heard(600))
        assertEquals(GpsAlertChange.SOUND, heard(630))
    }

    @Test
    fun `a placed packet ends the stretch`() {
        assertNull(heard(0))
        assertNull(heard(20, placed = true))
        assertNull(heard(40))
        assertEquals(GpsAlertChange.SOUND, heard(70))
        assertEquals(GpsAlertChange.REMOVE, heard(71, placed = true))
    }

    @Test
    fun `a GPS that keeps coming and going sounds at most every two minutes`() {
        heard(0)
        assertEquals(GpsAlertChange.SOUND, heard(30))
        alert.fixed(goodFix)
        heard(40)
        assertEquals(GpsAlertChange.SHOW_QUIETLY, heard(70))
        alert.fixed(goodFix)
        heard(130)
        assertEquals(GpsAlertChange.SOUND, heard(160))
    }

    @Test
    fun `goes while the radio is away`() {
        heard(0)
        assertEquals(GpsAlertChange.SOUND, heard(30))
        assertEquals(GpsAlertChange.REMOVE, alert.paused())
        assertNull(alert.paused())
    }
}
