package com.dietcam.app

import android.content.Context
import android.content.SharedPreferences

data class DietSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val secret: String = "",
)

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dietcam", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var secret: String
        get() = prefs.getString("secret", "") ?: ""
        set(value) = prefs.edit().putString("secret", value.trim()).apply()

    fun snapshot(): DietSettings = DietSettings(baseUrl, apiKey, secret)

    fun isConfigured(): Boolean = baseUrl.isNotBlank() && secret.isNotBlank()
}
