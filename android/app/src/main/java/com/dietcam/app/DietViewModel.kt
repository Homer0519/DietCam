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
        if (current.baseUrl.isBlank()) {
            _state.value = DietState.Failed("请先填写服务器地址", now())
            return
        }
        _state.value = DietState.Uploading
        viewModelScope.launch {
            try {
                val json = DietApi(current).health()
                _state.value = DietState.Failed(
                    "连接成功 ✅ 插件版本 " + json.optString("version", "?") + "，数据目录 " +
                        json.optString("data_dir", "?"),
                    now(),
                )
            } catch (e: Exception) {
                _state.value = DietState.Failed("连接失败：" + (e.message ?: e.toString()), now())
            }
        }
    }

    fun submit(file: File) {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _state.value = DietState.Failed("请先点右上角设置，填写服务器地址与签名密钥", now())
            return
        }
        _state.value = DietState.Uploading
        viewModelScope.launch {
            try {
                val json = DietApi(current).analyze(file, "")
                _state.value = DietState.Success(parse(json), now())
            } catch (e: Exception) {
                _state.value = DietState.Failed(e.message ?: "未知错误", now())
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
