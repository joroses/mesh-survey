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

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.nowMillis

/** A GPS fix, timed on the phone's monotonic clock so its age is exact. Mock fixes never become one. */
data class SurveyFix(val elapsedRealtimeMs: Long, val latitude: Double, val longitude: Double, val accuracyM: Float?) {
    override fun toString(): String = "SurveyFix(location redacted)"
}

/** The phone's clocks: wall time for the record, and the monotonic clock that times GPS fixes. */
interface SurveyClock {
    fun wallTimeMs(): Long

    fun elapsedRealtimeMs(): Long
}

@Single
class AndroidSurveyClock : SurveyClock {
    override fun wallTimeMs(): Long = nowMillis

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
}

/**
 * The phone's GPS while a survey records. The app's own location source asks the fused provider every 30 s and drops
 * each fix's age, so the survey keeps its own 1 s GPS request.
 */
interface SurveyLocation {
    /** Whether GPS can start: precise location granted and the GPS provider on. */
    fun isAvailable(): Boolean

    fun start(onFix: (SurveyFix) -> Unit)

    fun stop()
}

@Single
class AndroidSurveyLocation(private val context: Application) : SurveyLocation {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListenerCompat? = null

    override fun isAvailable(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @Suppress("MissingPermission") // isAvailable() checks it, and the recorder calls that first.
    @Synchronized
    override fun start(onFix: (SurveyFix) -> Unit) {
        if (listener != null) return
        val callback = LocationListenerCompat { location -> location.toFix()?.let(onFix) }
        listener = callback
        LocationManagerCompat.requestLocationUpdates(
            manager,
            LocationManager.GPS_PROVIDER,
            LocationRequestCompat.Builder(INTERVAL_MS)
                .setMinUpdateIntervalMillis(INTERVAL_MS)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build(),
            callback,
            Looper.getMainLooper(),
        )
    }

    @Synchronized
    override fun stop() {
        listener?.let { LocationManagerCompat.removeUpdates(manager, it) }
        listener = null
    }

    private fun Location.toFix(): SurveyFix? {
        if (LocationCompat.isMock(this) || !latitude.isFinite() || !longitude.isFinite()) return null
        return SurveyFix(
            elapsedRealtimeMs = elapsedRealtimeNanos / NANOS_PER_MILLI,
            latitude = latitude,
            longitude = longitude,
            accuracyM = accuracy.takeIf { hasAccuracy() },
        )
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
