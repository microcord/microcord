package com.discord.utilities.networking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
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
            override fun onAvailable(network: Network) = updateNetworkState(context, null)
            override fun onLost(network: Network) = updateNetworkState(context, null)
            override fun onUnavailable() = updateNetworkState(context, null)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                updateNetworkState(context, null)
        }
        val registerAllNetworks = {
            runCatching {
                connectivityManager.registerNetworkCallback(
                    android.net.NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback,
                )
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

    private fun registerBroadcastConnectivityNetworkMonitor(context: Context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) =
                        updateNetworkState(receiverContext, intent)
                },
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
            )
        }
    }

    private fun registerBroadcastAirplaneMode(context: Context) {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiverContext: Context, intent: Intent) =
                        updateNetworkState(receiverContext, null)
                },
                IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            )
        }
    }

    private fun readState(context: Context, intent: Intent?): State {
        val state = when {
            isAirplaneModeOn(context) -> State.OFFLINE_AIRPLANE_MODE
            isDeviceConnected(context, intent) -> State.ONLINE
            else -> State.OFFLINE
        }
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
    private fun isDeviceConnected(context: Context, intent: Intent?): Boolean = try {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val candidates: List<NetworkInfo?> = listOf(
                connectivityManager.activeNetworkInfo,
                intent?.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO),
            ) + connectivityManager.allNetworks.map(connectivityManager::getNetworkInfo)
            @Suppress("DEPRECATION")
            candidates.any { it != null && it.isConnectedOrConnecting }
        }
    } catch (_: Exception) {
        true
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}
