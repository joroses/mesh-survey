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

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** How long packets must go unplaced before the alert shows: a short tunnel or a city block is not worth one. */
internal val GPS_ALERT_AFTER = 30.seconds

/** The alert sounds at most once in this long; a GPS that keeps coming and going shows it quietly in between. */
internal val GPS_ALERT_QUIET = 2.minutes

enum class GpsAlertChange {
    SOUND,
    SHOW_QUIETLY,
    REMOVE,
}

/**
 * Decides the "No GPS fix" alert, which tells a surveyor with the screen off that packets are being heard while
 * SigSleuth can't place them. It looks at packets, not at the GPS alone: a bad fix with nothing heard loses nothing. It
 * shows once the packets SigSleuth will skip for their fix have kept coming for [GPS_ALERT_AFTER], and goes when a fix
 * good enough to place a packet comes back. Times are the measurements' wall clock.
 */
class SurveyGpsAlert {
    private var unplacedSince: Long? = null
    private var showing = false
    private var soundedAt: Long? = null

    fun measured(measurement: SurveyMeasurement): GpsAlertChange? {
        // Its cell is set exactly when SigSleuth will place it (sigSleuthCell).
        if (measurement.cellX != null) return clear()
        val now = measurement.heardAtMs
        val since = unplacedSince ?: now.also { unplacedSince = it }
        return if (showing || now - since < GPS_ALERT_AFTER.inWholeMilliseconds) null else show(now)
    }

    private fun show(now: Long): GpsAlertChange {
        showing = true
        val quiet = soundedAt?.let { now - it < GPS_ALERT_QUIET.inWholeMilliseconds } == true
        if (!quiet) soundedAt = now
        return if (quiet) GpsAlertChange.SHOW_QUIETLY else GpsAlertChange.SOUND
    }

    /** A fix that would place a packet heard now ends the stretch; one that wouldn't changes nothing yet. */
    fun fixed(fix: SurveyFix): GpsAlertChange? =
        if (sigSleuthCell(fix.latitude, fix.longitude, fix.accuracyM, fixAgeMs = 0) != null) clear() else null

    /** The radio went away: GPS is paused and nothing is heard, so there is nothing to warn about. */
    fun paused(): GpsAlertChange? = clear()

    private fun clear(): GpsAlertChange? {
        unplacedSince = null
        return if (showing) GpsAlertChange.REMOVE.also { showing = false } else null
    }
}
