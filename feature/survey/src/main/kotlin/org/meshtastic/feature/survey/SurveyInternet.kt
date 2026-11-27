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

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.core.annotation.Single
import org.meshtastic.core.common.util.safeCatching

/** Whether an upload can reach the internet now, so a run without it waits instead of timing out. */
interface SurveyInternet {
    fun online(): Boolean

    /** [online] now and after each change, so a wait can end when the phone gets the internet back. */
    fun changes(): Flow<Boolean>
}

/** Online when Android has checked that the default network reaches the internet. Unreadable counts as online. */
@Single
class ConnectivitySurveyInternet(private val context: Application) : SurveyInternet {
    override fun online(): Boolean = safeCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        manager
            .getNetworkCapabilities(manager.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
        .getOrDefault(true)

    override fun changes(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                // A new network first comes without VALIDATED; Android adds it once it has reached the internet.
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                }

                override fun onLost(network: Network) {
                    trySend(false)
                }
            }
        trySend(online())
        // Unregistered, the flow keeps its first value, and waits end on their timers as before.
        val registered = safeCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) safeCatching { manager.unregisterNetworkCallback(callback) } }
    }
        .distinctUntilChanged()
}
