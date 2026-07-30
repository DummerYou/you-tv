package com.youtv.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _connected = MutableStateFlow(manager.activeNetwork != null)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    init {
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _connected.value = true
                }

                override fun onLost(network: Network) {
                    _connected.value = manager.activeNetwork != null
                }
            })
        }
    }
}
