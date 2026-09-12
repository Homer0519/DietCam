package com.dietcam.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** 主页状态。 */
data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val summary: DaySummary? = null,
    val error: String? = null,
)

/** 拍照页状态机：取景 -> 定格确认 -> 分析中 -> 结果。 */
sealed interface CaptureUiState {
    data object Live : CaptureUiState

    /** 画面已定格，等用户确认或重拍。 */
    data class Reviewing(val file: File, val bitmap: Bitmap) : CaptureUiState

    /** 正在分析，[streamed] 是模型已经吐出的内容。 */
    data class Analyzing(val streamed: String, val engine: String = "") : CaptureUiState

    data class Done(val record: MealRecord) : CaptureUiState
    data class Failed(val message: String) : CaptureUiState
}

/** 中性提示弹窗（连接测试、说明等）。 */
data class Notice(val title: String, val message: String)

class DietViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home.asStateFlow()

    private val _capture = MutableStateFlow<CaptureUiState>(CaptureUiState.Live)
    val capture: StateFlow<CaptureUiState> = _capture.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _configVersion = MutableStateFlow(0)
    val configVersion: StateFlow<Int> = _configVersion.asStateFlow()

    private var analyzeJob: Job? = null

    // ------------------------------------------------------------- 设置

    fun settings(): DietSettings = store.snapshot()

    fun isConfigured(): Boolean = store.isConfigured()

    fun saveSettings(baseUrl: String, apiKey: String, secret: String) {
        store.baseUrl = baseUrl
        store.apiKey = apiKey
        store.secret = secret
        _configVersion.value = _configVersion.value + 1
        refreshHome()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    fun testConnection() {
        val current = store.snapshot()
        val missing = mutableListOf<String>()
        if (current.baseUrl.isBlank()) missing.add("服务器地址")
        if (current.apiKey.isBlank()) missing.add("AstrBot API Key")
        if (current.secret.isBlank()) missing.add("签名密钥")
        if (missing.isNotEmpty()) {
            _notice.value = Notice("还差几项没填", "请先填写：" + missing.joinToString("、"))
            return
        }
        viewModelScope.launch {
            try {
                val json = DietApi(current).health()
                val features = json.optJSONObject("features")
                val stream = features?.optBoolean("stream", false) ?: false
                _notice.value = Notice(
                    "连接成功 ✅",
                    buildString {
                        append("插件版本：").append(json.optString("version", "?"))
                        append("\n流式分析：").append(if (stream) "已启用" else "未启用（将自动降级）")
                        append("\n数据目录：").append(json.optString("data_dir", "?"))
                        append("\n\n两把钥匙都对上了，可以开始记录。")
                    },
                )
            } catch (e: Exception) {
                _notice.value = Notice("连接失败", DietApi.friendlyMessage(e))
            }
        }
    }

    // -------------------------------------------------------------- 主页

    fun refreshHome() {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _home.value = HomeUiState(loading = false, error = "还没有配置服务器")
            return
        }
        viewModelScope.launch {
            _home.value = _home.value.copy(refreshing = _home.value.summary != null, error = null)
            try {
                val json = DietApi(current).summary()
                _home.value = HomeUiState(
                    loading = false,
                    refreshing = false,
                    summary = JsonParse.summary(json),
                )
            } catch (e: Exception) {
                _home.value = _home.value.copy(
                    loading = false,
                    refreshing = false,
                    error = DietApi.friendlyMessage(e),
                )
            }
        }
    }

    fun saveTargets(targets: Map<String, Double>) {
        val current = store.snapshot()
        if (current.baseUrl.isBlank()) return
        viewModelScope.launch {
            try {
                DietApi(current).updateTargets(targets)
                refreshHome()
            } catch (e: Exception) {
                _notice.value = Notice("保存目标失败", DietApi.friendlyMessage(e))
            }
        }
    }

    // -------------------------------------------------------- 拍照与分析

    /** 快门按下、照片已落盘：解码出来定格显示。 */
    fun onPhotoCaptured(file: File) {
        viewModelScope.launch {
            val bitmap = decodeScaled(file)
            if (bitmap == null) {
                file.delete()
                _capture.value = CaptureUiState.Failed("照片读取失败，请重试")
                return@launch
            }
            _capture.value = CaptureUiState.Reviewing(file, bitmap)
        }
    }

    /** 重拍：丢掉刚才那张，回到取景。 */
    fun retake() {
        val state = _capture.value
        if (state is CaptureUiState.Reviewing) {
            state.file.delete()
        }
        _capture.value = CaptureUiState.Live
    }

    /** 确认这张照片，开始流式分析。 */
    fun confirmPhoto(note: String) {
        val state = _capture.value
        if (state !is CaptureUiState.Reviewing) return
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _capture.value = CaptureUiState.Failed("请先在设置里填写服务器地址与密钥")
            return
        }
        startAnalyze(state.file, note) { api, onDelta ->
            api.analyzeStream(state.file, note, onDelta)
        }
    }

    /** 纯文字记录（同样走流式）。 */
    fun submitText(text: String) {
        val desc = text.trim()
        if (desc.isEmpty()) return
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) {
            _capture.value = CaptureUiState.Failed("请先在设置里填写服务器地址与密钥")
            return
        }
        startAnalyze(null, desc) { api, onDelta ->
            api.analyzeTextStream(desc, onDelta)
        }
    }

    private fun startAnalyze(
        file: File?,
        note: String,
        call: suspend (DietApi, (String) -> Unit) -> org.json.JSONObject,
    ) {
        analyzeJob?.cancel()
        val api = DietApi(store.snapshot())
        val buffer = StringBuilder()
        _capture.value = CaptureUiState.Analyzing("")
        analyzeJob = viewModelScope.launch {
            try {
                val record = call(api) { piece ->
                    buffer.append(piece)
                    _capture.value = CaptureUiState.Analyzing(buffer.toString())
                }
                _capture.value = CaptureUiState.Done(JsonParse.record(record))
                refreshHome()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _capture.value = CaptureUiState.Failed(DietApi.friendlyMessage(e))
            } finally {
                file?.delete()
            }
        }
    }

    /** 用户中途放弃分析。 */
    fun cancelAnalysis() {
        analyzeJob?.cancel()
        analyzeJob = null
        _capture.value = CaptureUiState.Live
    }

    /** 回到取景，准备下一张。 */
    fun resetCapture() {
        analyzeJob?.cancel()
        analyzeJob = null
        val state = _capture.value
        if (state is CaptureUiState.Reviewing) {
            state.file.delete()
        }
        _capture.value = CaptureUiState.Live
    }

    // ------------------------------------------------- 档案 / 日历 / 历史

    private val _profile = MutableStateFlow<ProfileInfo?>(null)
    val profile: StateFlow<ProfileInfo?> = _profile.asStateFlow()

    private val _calendar = MutableStateFlow<CalendarMonth?>(null)
    val calendar: StateFlow<CalendarMonth?> = _calendar.asStateFlow()

    private val _history = MutableStateFlow<List<MealRecord>>(emptyList())
    val history: StateFlow<List<MealRecord>> = _history.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refreshProfile() {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) return
        viewModelScope.launch {
            runCatching { DietApi(current).profile() }
                .onSuccess { _profile.value = ProfileParse.info(it) }
                .onFailure { _notice.value = Notice("读取档案失败", DietApi.friendlyMessage(it)) }
        }
    }

    fun saveProfile(profile: BodyProfile, onDone: () -> Unit = {}) {
        val current = store.snapshot()
        if (current.baseUrl.isBlank()) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val json = DietApi(current).saveProfile(
                    mapOf(
                        "height_cm" to profile.heightCm,
                        "weight_kg" to profile.weightKg,
                        "age" to profile.age,
                        "sex" to profile.sex,
                        "activity" to profile.activity,
                        "goal" to profile.goal,
                    )
                )
                _profile.value = ProfileParse.info(json)
                _notice.value = Notice(
                    "目标已按新档案重算",
                    "每日 " + json.optJSONObject("targets")?.optDouble("calories_kcal", 0.0)
                        ?.toInt().toString() + " 千卡\n" +
                        "蛋白质 " + json.optJSONObject("targets")?.optDouble("protein_g", 0.0)
                        ?.toInt().toString() + " g",
                )
                refreshHome()
                onDone()
            } catch (e: Exception) {
                _notice.value = Notice("保存档案失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshCalendar(month: String) {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) return
        viewModelScope.launch {
            runCatching { DietApi(current).calendar(month) }
                .onSuccess { _calendar.value = ProfileParse.calendar(it) }
                .onFailure { _notice.value = Notice("读取日历失败", DietApi.friendlyMessage(it)) }
        }
    }

    fun refreshHistory(days: Int = 30) {
        val current = store.snapshot()
        if (current.baseUrl.isBlank() || current.secret.isBlank()) return
        viewModelScope.launch {
            runCatching { DietApi(current).history(days) }
                .onSuccess { json ->
                    val list = mutableListOf<MealRecord>()
                    json.optJSONArray("records")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { list.add(JsonParse.record(it)) }
                        }
                    }
                    _history.value = list
                }
                .onFailure { _notice.value = Notice("读取回忆失败", DietApi.friendlyMessage(it)) }
        }
    }

    // ------------------------------------------------------ 单条记录操作

    fun updateRecord(date: String, record: MealRecord, fields: Map<String, Any>, onDone: () -> Unit = {}) {
        val current = store.snapshot()
        _busy.value = true
        viewModelScope.launch {
            try {
                DietApi(current).updateRecord(date, record.id, fields)
                afterRecordChange()
                onDone()
            } catch (e: Exception) {
                _notice.value = Notice("修改失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteRecord(date: String, record: MealRecord, onDone: () -> Unit = {}) {
        val current = store.snapshot()
        _busy.value = true
        viewModelScope.launch {
            try {
                DietApi(current).deleteRecord(date, record.id)
                afterRecordChange()
                onDone()
            } catch (e: Exception) {
                _notice.value = Notice("删除失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    fun reanalyzeRecord(date: String, record: MealRecord, instruction: String, onDone: () -> Unit = {}) {
        val current = store.snapshot()
        _busy.value = true
        viewModelScope.launch {
            try {
                DietApi(current).reanalyzeRecord(date, record.id, instruction)
                afterRecordChange()
                _notice.value = Notice("已重新分析", "模型按新说明更新了这条记录。")
                onDone()
            } catch (e: Exception) {
                _notice.value = Notice("重新分析失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    /** 记录被改动后，把三个页面都刷新一遍。 */
    private fun afterRecordChange() {
        refreshHome()
        _calendar.value?.let { refreshCalendar(it.month) }
        refreshHistory()
    }

    private suspend fun decodeScaled(file: File): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1600) {
            sample *= 2
        }
        runCatching {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        analyzeJob?.cancel()
        (_capture.value as? CaptureUiState.Reviewing)?.let { it.file.delete() }
    }
}
