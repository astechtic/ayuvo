package com.ayuvo.health.records.processing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Whether an internet-capable network is up (cloud AI waits for connectivity otherwise). */
object NetworkState {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
