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
package org.meshtastic.feature.survey.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SignalMarginTest {
    @Test
    fun `the floor drops 2_5 dB per spreading factor`() {
        assertEquals(7.5f, fadeMargin(0f, 7))
        assertEquals(15f, fadeMargin(-2.5f, 11))
        assertEquals(20f, fadeMargin(0f, 12))
    }

    @Test
    fun `colours follow the map's stops and stop at both ends`() {
        assertEquals(Color(0xFFD0453A), marginColor(-3f))
        assertEquals(Color(0xFFD0453A), marginColor(0f))
        assertEquals(Color(0xFFE0A526), marginColor(6f))
        assertEquals(Color(0xFF1F7A3F), marginColor(25f))
        assertEquals(Color(0xFF1F7A3F), marginColor(40f))
    }

    @Test
    fun `between stops the channels mix in proportion`() {
        // Halfway from red (D0 45 3A) to amber (E0 A5 26).
        assertEquals(Color(0xFFD87530), marginColor(3f))
    }
}
