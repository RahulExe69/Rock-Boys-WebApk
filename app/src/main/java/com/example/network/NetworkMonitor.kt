package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class NetworkMonitor(context: Context) {
    private val connectivityManager: ConnectivityManager? = try {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (e: Throwable) {
        null
    }

    val isConnected: Flow<Boolean> = callbackFlow {
        try {
            trySend(checkCurrentConnection())
        } catch (e: Throwable) {
            trySend(true)
        }

        val manager = connectivityManager
        if (manager == null) {
            try {
                trySend(false)
            } catch (e: Throwable) {
                // Ignore flow state failures
            }
            close()
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    trySend(true)
                } catch (e: Throwable) {
                    // Ignore flow state failures
                }
            }

            override fun onLost(network: Network) {
                try {
                    trySend(false)
                } catch (e: Throwable) {
                    // Ignore flow state failures
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        var isCallbackRegistered = false
        try {
            manager.registerNetworkCallback(request, callback)
            isCallbackRegistered = true
        } catch (e: Throwable) {
            Log.e("NetworkMonitor", "Could not register network callback", e)
            try {
                trySend(checkCurrentConnection())
            } catch (ex: Throwable) {
                // Ignore flow state failures
            }
        }

        awaitClose {
            if (isCallbackRegistered) {
                try {
                    manager.unregisterNetworkCallback(callback)
                } catch (e: Throwable) {
                    // Ignore if already unregistered
                }
            }
        }
    }

    fun isCurrentlyConnected(): Boolean {
        return checkCurrentConnection()
    }

    private fun checkCurrentConnection(): Boolean {
        val manager = connectivityManager ?: return false
        return try {
            val activeNetwork = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Throwable) {
            false
        }
    }
}
