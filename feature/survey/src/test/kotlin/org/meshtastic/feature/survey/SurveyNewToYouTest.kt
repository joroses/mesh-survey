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

class SurveyNewToYouTest {
    private fun session(id: String, news: SurveyNews?) = SurveySession(
        id,
        0,
        receiverNodeId = 1,
        receiverName = "",
        modemPreset = null,
        spreadingFactor = 11,
        bandwidthKhz = 250f,
        frequencyMhz = null,
        appVersion = "test",
        news = news,
    )

    private val offline =
        session("offline", SurveyNews(40, 21, 5, localNewCells = 7, localNewRelayCells = 30, localNewDirectCells = 2))
    private val uploaded =
        session(
            "uploaded",
            SurveyNews(10, 8, 1, newCells = 4, localNewCells = 3, localNewRelayCells = 5),
        )
    private val uncounted = session("uncounted", news = null)

    @Test
    fun `the totals add every session's cells new to you, and the map's cells once SigSleuth answered`() {
        assertEquals(SurveyTotals(7, 5, mapCells = null), surveyTotals(listOf(offline, uncounted), null, 5))
        assertEquals(SurveyTotals(10, 5, mapCells = 4), surveyTotals(listOf(offline, uploaded), null, 5))
        assertNull(surveyTotals(listOf(uncounted), null, directSenders = 0))
    }

    @Test
    fun `deleted sessions' map cells stay in the total`() {
        assertEquals(
            SurveyTotals(10, 5, mapCells = 10),
            surveyTotals(listOf(offline, uploaded), SurveyDeleted(newCells = 6), directSenders = 5),
        )
        assertEquals(SurveyTotals(0, 0, mapCells = 6), surveyTotals(emptyList(), SurveyDeleted(newCells = 6), 0))
    }
}
