package com.example.network

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

class ConnectionCutoffVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null

    companion object {
        private const val TAG = "ConnectionCutoffVpn"
        var instance: ConnectionCutoffVpnService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "START_VPN") {
            establishVpn()
        } else if (action == "STOP_VPN") {
            stopVpn()
        }
        return START_STICKY
    }

    private fun establishVpn() {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("RaidReloadVpn")
                // Define local loopback configuration to absorb packet transfers completely offline
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            // Customize strictly for Clash of Clans and omit any other applications
            try {
                builder.addAllowedApplication("com.supercell.clashofclans")
                Log.d(TAG, "Clash of Clans app successfully bound to selective drop tunnel.")
            } catch (e: Exception) {
                Log.w(TAG, "Clash of Clans app package not found on device: ${e.localizedMessage}")
            }

            vpnInterface = builder.establish()
            Log.d(TAG, "Selective VPN interface established successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Could not establish VPN routing interface: ${e.message}")
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed closing VPN descriptor safely: ${e.message}")
        }
        vpnInterface = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        if (instance == this) {
            instance = null
        }
    }
}
