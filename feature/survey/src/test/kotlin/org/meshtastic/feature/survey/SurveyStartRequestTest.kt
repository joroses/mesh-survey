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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SurveyStartRequestTest {
    private var now = 1_000_000L
    private val request =
        SurveyStartRequest(
            object : SurveyClock {
                override fun wallTimeMs() = now

                override fun elapsedRealtimeMs() = now
            },
        )

    @Test
    fun `a fresh request is taken once`() {
        request.request()
        assertTrue(request.take())
        assertFalse(request.take())
        assertNull(request.pending.value)
    }

    @Test
    fun `a stale request lapses and is cleared`() {
        request.request()
        now += SurveyStartRequest.TTL_MS + 1
        assertFalse(request.take())
        assertNull(request.pending.value)
    }

    @Test
    fun `nothing requested is nothing to take`() {
        assertFalse(request.take())
    }
}
