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

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.koin.core.annotation.Single
import org.meshtastic.core.navigation.DEEP_LINK_BASE_URI

/** Opens the survey screen above Settings, where its menu item is (`DeepLinkRouter`'s `settings/survey`). */
fun surveyScreenIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, "$DEEP_LINK_BASE_URI/settings/survey".toUri())
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Long-pressing the launcher icon offers the survey screen, and the launcher lets it be dragged to the home screen. */
fun publishSurveyShortcut(context: Context) {
    val shortcut =
        ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
            .setShortLabel(context.getString(R.string.survey_shortcut))
            .setLongLabel(context.getString(R.string.survey_title))
            .setIcon(IconCompat.createWithResource(context, R.drawable.survey_ic_shortcut))
            .setIntent(surveyScreenIntent(context))
            .build()
    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
}

/**
 * A start the Quick Settings tile asked for. The survey screen it opens carries it out as if Start was pressed,
 * permission requests included, since starting a location service from the background can be refused. It lapses after
 * [TTL_MS], so a screen opened much later, say after the app's first-run intro, does not start by itself.
 */
@Single
class SurveyStartRequest(private val clock: SurveyClock) {
    private val requested = MutableStateFlow<Long?>(null)

    /** When the pending request was made, or null; the screen watches it and calls [take]. */
    val pending: StateFlow<Long?> = requested.asStateFlow()

    fun request() {
        requested.value = clock.elapsedRealtimeMs()
    }

    /** True once per request, and only while it is fresh. */
    fun take(): Boolean {
        val at = requested.getAndUpdate { null } ?: return false
        return clock.elapsedRealtimeMs() - at <= TTL_MS
    }

    companion object {
        const val TTL_MS = 30_000L
    }
}

private const val SHORTCUT_ID = "survey"
