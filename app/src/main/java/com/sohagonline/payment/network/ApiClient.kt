package com.sohagonline.payment.network

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sohagonline.payment.data.AppPrefs
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ApiClient private constructor(private val context: Context) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "ApiClient"

        @Volatile
        private var instance: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient(context.applicationContext).also { instance = it }
            }
        }
    }

    data class ApiResponse(
        val isSuccess: Boolean,
        val statusCode: String,
        val message: String
    )

    /**
     * Connect and authenticate device with the server.
     * Endpoint: /api/device-connect
     */
    fun connectDevice(serverUrl: String, userEmail: String, deviceKey: String): ApiResponse {
        return try {
            val ip = IpHelper.getDeviceIp(client)
            val cleanUrl = "${serverUrl.trimEnd('/')}/api/device-connect"

            val formBody = FormBody.Builder()
                .add("user_email", userEmail)
                .add("device_key", deviceKey)
                .add("device_ip", ip)
                .build()

            val request = Request.Builder()
                .url(cleanUrl)
                .post(formBody)
                .header("User-Agent", "SohagOnlinePayment-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "deviceConnect Response: $body")

                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val status = json.get("status")?.asString ?: "0"
                    val msg = json.get("message")?.asString ?: ""

                    ApiResponse(
                        isSuccess = (status == "1"),
                        statusCode = status,
                        message = msg
                    )
                } else {
                    ApiResponse(false, "0", "HTTP Error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            ApiResponse(false, "0", e.message ?: "Connection error")
        }
    }

    /**
     * Send incoming SMS to the server.
     * Endpoint: /api/add-data
     */
    fun sendSms(address: String, message: String): ApiResponse {
        val prefs = AppPrefs(context)
        if (!prefs.isConfigured()) {
            return ApiResponse(false, "0", "App is not configured with Server URL, Email, or Device Key")
        }

        return try {
            val ip = IpHelper.getDeviceIp(client)
            val cleanUrl = "${prefs.serverUrl.trimEnd('/')}/api/add-data"

            val formBody = FormBody.Builder()
                .add("user_email", prefs.userEmail)
                .add("device_key", prefs.deviceKey)
                .add("device_ip", ip)
                .add("address", address.trim())
                .add("message", message.trim())
                .build()

            val request = Request.Builder()
                .url(cleanUrl)
                .post(formBody)
                .header("User-Agent", "SohagOnlinePayment-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(TAG, "addMessage Response for $address: $body")

                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JsonParser.parseString(body).asJsonObject
                    val status = json.get("status")?.asString ?: "0"
                    val msg = json.get("message")?.asString ?: ""

                    // Status 1 = Data inserted successfully or Already exists
                    val isSuccess = (status == "1")
                    ApiResponse(isSuccess, status, msg)
                } else {
                    ApiResponse(false, "0", "HTTP Error: ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send SMS failed: ${e.message}", e)
            ApiResponse(false, "0", e.message ?: "Network error")
        }
    }
}
