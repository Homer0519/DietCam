package com.dietcam.app

import android.content.Context
import android.content.SharedPreferences

/**
 * App 设置。
 *
 * 两种模式：
 *  · 默认（[localMode] = false）连 AstrBot 上的 astrbot_plugin_diet 插件
 *  · [localMode] = true 时不依赖 AstrBot，直接调用 [modelBaseUrl] 上的
 *    OpenAI 兼容视觉模型，照片与记录全部存在这台手机上
 */
data class DietSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val secret: String = "",
    val localMode: Boolean = false,
    val modelBaseUrl: String = "",
    val modelApiKey: String = "",
    val modelName: String = "",
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

    var localMode: Boolean
        get() = prefs.getBoolean("local_mode", false)
        set(value) = prefs.edit().putBoolean("local_mode", value).apply()

    var modelBaseUrl: String
        get() = prefs.getString("model_base_url", "") ?: ""
        set(value) = prefs.edit().putString("model_base_url", value.trim()).apply()

    var modelApiKey: String
        get() = prefs.getString("model_api_key", "") ?: ""
        set(value) = prefs.edit().putString("model_api_key", value.trim()).apply()

    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    fun snapshot(): DietSettings =
        DietSettings(baseUrl, apiKey, secret, localMode, modelBaseUrl, modelApiKey, modelName)

    fun isConfigured(): Boolean = if (localMode) {
        modelBaseUrl.isNotBlank() && modelName.isNotBlank()
    } else {
        baseUrl.isNotBlank() && secret.isNotBlank()
    }
}
