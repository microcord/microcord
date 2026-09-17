package com.discord.utilities.networking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Process-wide device connectivity source.
 *
 * Mirrors the reference `com.discord.utilities.networking.NetworkMonitor`: a [BehaviorSubject] of
 * [State] fed by a `ConnectivityManager.NetworkCallback` plus the legacy connectivity and airplane
 * mode broadcasts. Consumers observe [observeState]; [StoreConnectivity] projects it into the
 * user-facing connection banner state.
 *
 * The reference delegates the actual probing to `com.discord.utilities.io.NetworkUtils`. That
 * utility also owns unrelated download-manager behavior which is not reconstructed yet, so the
 * probe lives here as private helpers and should move once `NetworkUtils` is reconstructed.
 */
class NetworkMonitor(context: Context) {

    enum class State { ONLINE, OFFLINE, OFFLINE_AIRPLANE_MODE }

    private val applicationContext: Context = context.applicationContext
    private val networkStateSubject = BehaviorSubject.create(readState(applicationContext, null))
    // ponytail: allNetworks is empty on API 31+ even when Wi-Fi is up; trust callbacks.
    private val availableNetworks = CopyOnWriteArraySet<Network>()

    init {
        registerConnectivityNetworkMonitor(applicationContext)
    }

    fun observeState(): Observable<State> = networkStateSubject.onBackpressureBuffer().distinctUntilChanged()

    fun observeIsConnected(): Observable<Boolean> =
        observeState().map { it == State.ONLINE }.distinctUntilChanged()

    fun getState(): State = networkStateSubject.value ?: State.OFFLINE

    private fun updateNetworkState(context: Context, intent: Intent?) {
        networkStateSubject.onNext(readState(context, intent))
    }

    private fun registerConnectivityNetworkMonitor(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                availableNetworks.add(network)
                updateNetworkState(context, null)
            }
            override fun onLost(network: Network) {
                availableNetworks.remove(network)
                updateNetworkState(context, null)
            }
            override fun onUnavailable() {
                availableNetworks.clear()
                updateNetworkState(context, null)
            }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                availableNetworks.add(network)
                updateNetworkState(context, null)
            }
        }
        val registerAllNetworks = {
            runCatching {
                connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { registerAllNetworks() }
        } else {
            registerAllNetworks()
        }
        registerBroadcastConnectivityNetworkMonitor(context)
        registerBroadcastAirplaneMode(context)
    }

    @Suppress("DEPRECATION")
    private fun registerBroadcastConnectivityNetworkMonitor(context: Context) {
        registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) =
                    updateNetworkState(receiverContext, intent)
            },
            IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
        )
    }

    private fun registerBroadcastAirplaneMode(context: Context) {
        registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context, intent: Intent) =
                    updateNetworkState(receiverContext, null)
            },
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
        )
    }

    private fun registerReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }

    private fun readState(context: Context, intent: Intent?): State {
        val state = resolveNetworkState(isDeviceConnected(context, intent), isAirplaneModeOn(context))
        runCatching { Log.i(TAG, "network state -> $state (API ${Build.VERSION.SDK_INT})") }
        return state
    }

    private fun isAirplaneModeOn(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
    }.getOrDefault(false)

    /**
     * Reference behavior: an exception while probing is treated as connected so the client never
     * strands itself in a permanent offline banner on a device with a restricted ConnectivityManager.
     */
    private fun isDeviceConnected(context: Context, intent: Intent?): Boolean {
        return try {
            if (availableNetworks.isNotEmpty()) return true
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (isUsable(connectivityManager, connectivityManager.activeNetwork)) return true
            if (connectivityManager.allNetworks.any { isUsable(connectivityManager, it) }) return true
            @Suppress("DEPRECATION")
            sequenceOf(
                connectivityManager.activeNetworkInfo,
                intent?.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO),
                connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI),
            ).any { it != null && it.isConnectedOrConnecting }
        } catch (_: Exception) {
            true
        }
    }

    private fun isUsable(connectivityManager: ConnectivityManager, network: Network?): Boolean {
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) } ?: return false
        return isConnectedNetwork(
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            capabilities::hasTransport,
        )
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

internal fun resolveNetworkState(connected: Boolean, airplaneMode: Boolean): NetworkMonitor.State = when {
    connected -> NetworkMonitor.State.ONLINE // Wi-Fi can remain enabled in airplane mode.
    airplaneMode -> NetworkMonitor.State.OFFLINE_AIRPLANE_MODE
    else -> NetworkMonitor.State.OFFLINE
}

internal fun isConnectedNetwork(hasInternet: Boolean, hasTransport: (Int) -> Boolean): Boolean =
    hasInternet || hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        hasTransport(NetworkCapabilities.TRANSPORT_VPN)
