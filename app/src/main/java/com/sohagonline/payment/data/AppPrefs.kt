package com.sohagonline.payment.data

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sohag_payment_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_DEVICE_KEY = "device_key"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_LAST_CLEAN_TIME = "last_clean_time"
        private const val KEY_IS_CONNECTED = "is_connected"

        // Default server URL
        const val DEFAULT_SERVER_URL = "https://expensivepay.com"
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var userEmail: String
        get() = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value.trim()).apply()

    var deviceKey: String
        get() = prefs.getString(KEY_DEVICE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_KEY, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, "My Android Device") ?: "My Android Device"
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_IS_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_CONNECTED, value).apply()

    var lastCleanTime: Long
        get() = prefs.getLong(KEY_LAST_CLEAN_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CLEAN_TIME, value).apply()

    fun isConfigured(): Boolean {
        return userEmail.isNotBlank() && deviceKey.isNotBlank() && serverUrl.isNotBlank()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
