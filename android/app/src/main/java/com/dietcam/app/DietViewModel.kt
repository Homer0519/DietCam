package com.dietcam.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MealItem(
    val name: String,
    val portion: String,
    val kcal: Double,
)

data class MealResult(
    val title: String,
    val meal: String,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val items: List<MealItem>,
    val advice: String,
    val isFood: Boolean,
)

sealed interface DietState {
    data object Idle : DietState
    data object Uploading : DietState
    data class Success(val result: MealResult, val at: String) : DietState
    data class Failed(val message: String, val at: String) : DietState

    /** 中性提示（连接测试结果、配置说明等），用普通样式展示而非报错样式。 */
    data class Notice(val title: String, val message: String) : DietState
}

class DietViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    private val _state = MutableStateFlow<DietState>(DietState.Idle)
    val state: StateFlow<DietState> = _state.asStateFlow()

    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()

    fun settings(): DietSettings = store.snapshot()

    fun isConfigured(): Boolean = store.isConfigured()

    fun saveSettings(baseUrl: String, apiKey: String, secret: String) {
        store.baseUrl = baseUrl
        store.apiKey = apiKey
        store.secret = secret
        _configVersion.value = _configVersion.value + 1
    }

    fun reset() {
        _state.value = DietState.Idle
    }

    fun testConnection() {
        val current = store.snapshot()
        val missing = mutableListOf<String>()
        if (current.baseUrl.isBlank()) missing.add("服务器地址")
        if (current.apiKey.isBlank()) missing.add("AstrBot API Key")
        if (current.secret.isBlank()) missing.add("签名密钥")
        if (missing.isNotEmpty()) {
            _state.value = DietState.Notice(
                "还差几项没填",
                "请先填写：" + missing.joinToString("、"),
            )
            return
        }
        _state.value = DietState.Uploading
        viewModelScope.launch {
            try {
                val json = DietApi(current).health()
                _state.value = DietState.Notice(
                    "连接成功 ✅",
                    "插件版本：" + json.optString("version", "?") + "\n" +
                        "数据目录：" + json.optString("data_dir", "?") + "\n\n" +
                        "两把钥匙都对上了，可以开始记录。",
                )
            } catch (e: Exception) {
                _state.value = DietState.Notice("连接失败", DietApi.friendlyMessage(e))
            }
        }
    }

    /** 纯文字记录。 */
    fun submitText(text: String) {
        val desc = text.trim()
        if (desc.isEmpty()) {
            return
        }
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _state.value = DietState.Failed("请先点右上角设置，填写服务器地址与签名密钥", now())
            return
        }
        _state.value = DietState.Uploading
        viewModelScope.launch {
            try {
                val json = DietApi(current).analyzeText(desc)
                _state.value = DietState.Success(parse(json), now())
            } catch (e: Exception) {
                _state.value = DietState.Failed(DietApi.friendlyMessage(e), now())
            }
        }
    }

    fun submit(file: File, note: String = "") {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _state.value = DietState.Failed("请先点右上角设置，填写服务器地址与签名密钥", now())
            return
        }
        _state.value = DietState.Uploading
        viewModelScope.launch {
            try {
                val json = DietApi(current).analyze(file, note)
                _state.value = DietState.Success(parse(json), now())
            } catch (e: Exception) {
                _state.value = DietState.Failed(DietApi.friendlyMessage(e), now())
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    private fun now(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun parse(json: JSONObject): MealResult {
        val record = json.optJSONObject("record") ?: json
        val items = mutableListOf<MealItem>()
        val array = record.optJSONArray("items")
        if (array != null) {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                items.add(
                    MealItem(
                        name = obj.optString("name", "?"),
                        portion = obj.optString("portion", ""),
                        kcal = obj.optDouble("calories_kcal", 0.0),
                    )
                )
            }
        }
        return MealResult(
            title = record.optString("title", "一餐"),
            meal = record.optString("meal", ""),
            kcal = record.optDouble("calories_kcal", 0.0),
            protein = record.optDouble("protein_g", 0.0),
            carbs = record.optDouble("carbs_g", 0.0),
            fat = record.optDouble("fat_g", 0.0),
            items = items,
            advice = record.optString("advice", ""),
            isFood = record.optBoolean("is_food", true),
        )
    }
}
