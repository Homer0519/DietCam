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

    // 注意：这里存的是「清理过」的值。粘贴进来的 key 常带换行，
    // 只 trim 首尾没用（换行可能夹在中间），必须把所有空白都去掉，
    // 否则 OkHttp 会抛 Unexpected char 0x0a in Authorization value。

    var baseUrl: String
        get() = prefs.getString("base_url", "") ?: ""
        set(value) = prefs.edit().putString("base_url", sanitizeUrl(value)).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", sanitizeKey(value)).apply()

    var secret: String
        get() = prefs.getString("secret", "") ?: ""
        set(value) = prefs.edit().putString("secret", sanitizeKey(value)).apply()

    var localMode: Boolean
        get() = prefs.getBoolean("local_mode", false)
        set(value) = prefs.edit().putBoolean("local_mode", value).apply()

    var modelBaseUrl: String
        get() = prefs.getString("model_base_url", "") ?: ""
        set(value) = prefs.edit().putString("model_base_url", sanitizeUrl(value)).apply()

    var modelApiKey: String
        get() = prefs.getString("model_api_key", "") ?: ""
        set(value) = prefs.edit().putString("model_api_key", sanitizeKey(value)).apply()

    var modelName: String
        get() = prefs.getString("model_name", "") ?: ""
        set(value) = prefs.edit().putString("model_name", sanitizeName(value)).apply()

    /** 是否已经弹过「填身高体重」的引导。只弹一次，用户点了「以后再说」也不再烦他。 */
    var profilePrompted: Boolean
        get() = prefs.getBoolean("profile_prompted", false)
        set(value) = prefs.edit().putBoolean("profile_prompted", value).apply()

    /** 上次检查更新的时间戳（毫秒）。启动时一天最多查一次，免得老骚扰用户。 */
    var lastUpdateCheck: Long
        get() = prefs.getLong("last_update_check", 0L)
        set(value) = prefs.edit().putLong("last_update_check", value).apply()

    fun snapshot(): DietSettings =
        DietSettings(baseUrl, apiKey, secret, localMode, modelBaseUrl, modelApiKey, modelName)

    fun isConfigured(): Boolean = if (localMode) {
        modelBaseUrl.isNotBlank() && modelName.isNotBlank()
    } else {
        baseUrl.isNotBlank() && secret.isNotBlank()
    }
}
