package com.sohagonline.payment.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

object IpHelper {
    private var cachedIp: String? = null
    private var lastFetchTime: Long = 0

    fun getDeviceIp(client: OkHttpClient): String {
        // Return cached IP if fresh (within 10 minutes)
        if (!cachedIp.isNullOrBlank() && (System.currentTimeMillis() - lastFetchTime < 10 * 60 * 1000L)) {
            return cachedIp!!
        }

        // Try public IP resolution first
        try {
            val request = Request.Builder()
                .url("https://api.ipify.org")
                .header("User-Agent", "SohagOnlinePayment/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val ip = response.body?.string()?.trim()
                    if (!ip.isNullOrBlank() && ip.length <= 45) {
                        cachedIp = ip
                        lastFetchTime = System.currentTimeMillis()
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("IpHelper", "Failed to fetch public IP: ${e.message}")
        }

        // Fallback: Local network interface IPv4
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (!ip.isNullOrBlank()) {
                            cachedIp = ip
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("IpHelper", "Failed to get local IPv4: ${e.message}")
        }

        // Ultimate safe fallback (Server strictly requires non-empty string)
        return cachedIp ?: "127.0.0.1"
    }
}
