package com.sohagonline.payment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log

class NetworkReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (isNetworkConnected(context)) {
            Log.d(TAG, "Internet connection restored. Ensuring payment service is running...")
            PaymentForegroundService.startService(context)
        }
    }

    private fun isNetworkConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(network) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }
}
