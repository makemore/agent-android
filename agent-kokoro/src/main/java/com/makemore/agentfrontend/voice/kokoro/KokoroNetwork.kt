package com.makemore.agentfrontend.voice.kokoro

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean

/** Tells the engine whether the active network is metered, and when an unmetered one appears. */
internal interface KokoroNetworkMonitor {
    /** True when large transfers should be avoided (metered, cellular, or unknown). */
    fun isMetered(): Boolean

    /** Call [onUnmetered] once, on any thread, when the default network becomes unmetered. Close to stop waiting. */
    fun whenUnmetered(onUnmetered: () -> Unit): AutoCloseable

    companion object {
        /** For hosts/tests without a Context: always unmetered. */
        val UNMETERED = object : KokoroNetworkMonitor {
            override fun isMetered() = false
            override fun whenUnmetered(onUnmetered: () -> Unit): AutoCloseable {
                onUnmetered()
                return AutoCloseable {}
            }
        }
    }
}

/**
 * [ConnectivityManager]-backed monitor. Needs `ACCESS_NETWORK_STATE`, which
 * agent-kokoro's manifest declares (a normal, install-time permission). If the
 * state cannot be read, the network is treated as metered.
 */
internal class AndroidNetworkMonitor(context: Context) : KokoroNetworkMonitor {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?

    override fun isMetered(): Boolean = try {
        cm?.isActiveNetworkMetered ?: true
    } catch (e: SecurityException) {
        true
    }

    override fun whenUnmetered(onUnmetered: () -> Unit): AutoCloseable {
        val manager = cm ?: return AutoCloseable {}
        val fired = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    fired.compareAndSet(false, true)
                ) {
                    runCatching { manager.unregisterNetworkCallback(this) }
                    onUnmetered()
                }
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            AutoCloseable { if (fired.compareAndSet(false, true)) runCatching { manager.unregisterNetworkCallback(callback) } }
        } catch (e: RuntimeException) {
            // SecurityException without the permission, or too many callbacks: just don't auto-resume.
            AutoCloseable {}
        }
    }
}
