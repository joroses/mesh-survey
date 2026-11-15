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

/**
 * How far an SNR sits above the weakest the radio can still decode at this spreading factor, in dB: SF7 −7.5 dB, 2.5 dB
 * lower per step. SigSleuth's map colours its coverage cells by the same margin.
 */
internal fun fadeMargin(snr: Float, spreadingFactor: Int): Float =
    snr - (FLOOR_SF7_DB - FLOOR_STEP_DB * (spreadingFactor - SF7))

/**
 * A margin's colour on SigSleuth's coverage scale: red at the edge of decoding, amber, then greens up to 25 dB, mixed
 * channel by channel between the stops as the map's sidebar does.
 */
internal fun marginColor(margin: Float): Color {
    val clamped = margin.coerceIn(MARGIN_STOPS.first().first, MARGIN_STOPS.last().first)
    val upper = MARGIN_STOPS.indexOfFirst { (db, _) -> db >= clamped }.coerceAtLeast(1)
    val (lowDb, low) = MARGIN_STOPS[upper - 1]
    val (highDb, high) = MARGIN_STOPS[upper]
    val share = (clamped - lowDb) / (highDb - lowDb)
    return Color(
        red = low.red + share * (high.red - low.red),
        green = low.green + share * (high.green - low.green),
        blue = low.blue + share * (high.blue - low.blue),
    )
}

private const val SF7 = 7
private const val FLOOR_SF7_DB = -7.5f
private const val FLOOR_STEP_DB = 2.5f

/** dB above the floor and colour, as SigSleuth's map has them. */
private val MARGIN_STOPS =
    listOf(0f to Color(0xFFD0453A), 6f to Color(0xFFE0A526), 13f to Color(0xFF8FC04A), 25f to Color(0xFF1F7A3F))
