package com.aichathub.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.aichathub.app.data.SettingsRepository

/**
 * Monitors network state for download management.
 *
 * Responsibilities:
 *  - Detect network type (Wi-Fi, Mobile, Ethernet, VPN)
 *  - Enforce Wi-Fi only mode
 *  - Auto-resume downloads when network becomes available
 *  - Pause downloads when network is lost (Wi-Fi only mode)
 *
 * Usage:
 *  1. Register callback with [startWatching]
 *  2. Check [isNetworkBlocked] before starting downloads
 *  3. Unregister with [stopWatching] when done
 */
class DownloadNetworkMonitor(
    private val context: Context,
    private val settingsRepository: SettingsRepository? = null
) {

    companion object {
        private const val TAG = "DownloadNetworkMonitor"
    }

    interface NetworkCallback {
        fun onNetworkAvailable()
        fun onNetworkLost()
    }

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var networkCallback: NetworkCallback? = null

    /**
     * Starts monitoring network state.
     *
     * @param callback callback for network state changes
     */
    fun startWatching(callback: NetworkCallback) {
        this.networkCallback = callback
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        try {
            val cmCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")
                    callback.onNetworkAvailable()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost")
                    callback.onNetworkLost()
                }
            }
            cm.registerDefaultNetworkCallback(cmCallback)
            this.callback = cmCallback
            Log.i(TAG, "Network watcher registered")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback", e)
        }
    }

    /**
     * Stops monitoring network state.
     */
    fun stopWatching() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        callback?.let { cm?.unregisterNetworkCallback(it) }
        callback = null
        networkCallback = null
        Log.i(TAG, "Network watcher unregistered")
    }

    /**
     * Returns the current network type as a human-readable string.
     */
    fun currentNetworkType(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> null
        }
    }

    /**
     * Checks if downloads should be blocked due to Wi-Fi only mode.
     *
     * @return true if downloads should be blocked (Wi-Fi only + mobile data)
     */
    fun isNetworkBlocked(): Boolean {
        val wifiOnly = settingsRepository?.cachedWifiOnlyDownloads ?: false
        if (!wifiOnly) return false
        return currentNetworkType() == "Mobile"
    }

    /**
     * Checks if the current network is suitable for large downloads.
     * Returns true for Wi-Fi and Ethernet, false for Mobile.
     */
    fun isHighBandwidthNetwork(): Boolean {
        val type = currentNetworkType()
        return type == "Wi-Fi" || type == "Ethernet"
    }
}
