package com.dietcam.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * App 的数据后端。
 *
 * 两个实现：
 *  · [DietApi]      —— 连 AstrBot 上的 astrbot_plugin_diet 插件（照片与记录都在服务器上）
 *  · [LocalDietApi] —— 本地模式，完全不依赖 AstrBot：直连视觉模型，照片与记录都留在手机里
 *
 * 两者返回的 JSON 结构与插件 Web API 完全一致，所以上层页面和解析代码不用区分是谁在干活。
 */
interface DietBackend {

    suspend fun health(): JSONObject

    suspend fun summary(date: String? = null): JSONObject

    suspend fun records(date: String): JSONObject

    suspend fun updateTargets(targets: Map<String, Double>): JSONObject

    suspend fun analyzeStream(file: File, note: String, onDelta: (String) -> Unit): JSONObject

    suspend fun analyzeTextStream(text: String, onDelta: (String) -> Unit): JSONObject

    suspend fun photoBytes(date: String, name: String): ByteArray

    suspend fun profile(): JSONObject

    suspend fun saveProfile(fields: Map<String, Any>): JSONObject

    suspend fun calendar(month: String): JSONObject

    suspend fun history(days: Int, end: String? = null): JSONObject

    suspend fun updateRecord(date: String, id: String, fields: Map<String, Any>): JSONObject

    suspend fun deleteRecord(date: String, id: String): JSONObject

    suspend fun reanalyzeRecord(date: String, id: String, instruction: String): JSONObject
}

/** 按当前设置挑一个后端。 */
fun dietBackend(context: Context, settings: DietSettings): DietBackend =
    if (settings.localMode) {
        LocalDietApi(context, settings)
    } else {
        DietApi(settings, File(context.cacheDir, "photo-cache"))
    }
