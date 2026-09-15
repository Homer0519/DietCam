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
import kotlin.math.roundToInt

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

    /**
     * 画面已定格，等用户确认或重拍。
     *
     * [fromGallery] 区分这张图是刚拍的还是从相册选的 ——
     * 从相册选来的图本来就在相册里，确认时不能再往回存一份。
     */
    data class Reviewing(
        val file: File,
        val bitmap: Bitmap,
        val fromGallery: Boolean = false,
    ) : CaptureUiState

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

    /** 照片已就绪（刚拍的或从相册选的）：解码出来定格显示。 */
    fun onPhotoCaptured(file: File, fromGallery: Boolean = false) {
        viewModelScope.launch {
            val bitmap = decodeScaled(file)
            if (bitmap == null) {
                file.delete()
                _capture.value = CaptureUiState.Failed("照片读取失败，请重试")
                return@launch
            }
            _capture.value = CaptureUiState.Reviewing(file, bitmap, fromGallery)
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
                val resp = DietApi(current).reanalyzeRecord(date, record.id, instruction)
                val updated = resp.optJSONObject("record")?.let { JsonParse.record(it) }
                afterRecordChange()
                // 光说一句「已重新分析」用户看不出模型到底改了什么，这里把前后差别列出来
                _notice.value = Notice(
                    "已重新分析",
                    if (updated == null) {
                        "模型已按新的说明重新估算「" + record.title + "」。"
                    } else {
                        describeChanges(record, updated)
                    },
                )
                onDone()
            } catch (e: Exception) {
                _notice.value = Notice("重新分析失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
            }
        }
    }

    /** 把重新分析前后的差别写成人话，让用户一眼看到模型改了什么。 */
    private fun describeChanges(before: MealRecord, after: MealRecord): String {
        val lines = mutableListOf<String>()

        if (!after.isFood) {
            lines += "模型重新看过之后判断：这不是食物。"
            if (before.title != after.title) {
                lines += "标题　" + before.title + " → " + after.title
            }
            return lines.joinToString("\n")
        }

        val changed = before.title != after.title ||
            before.nutrition != after.nutrition ||
            before.advice != after.advice

        if (before.title != after.title) {
            lines += "标题　" + before.title + " → " + after.title
        }
        if (before.nutrition != after.nutrition) {
            lines += diffLine("热量", before.nutrition.kcal, after.nutrition.kcal, " 千卡")
            lines += diffLine("蛋白", before.nutrition.protein, after.nutrition.protein, " g")
            lines += diffLine("碳水", before.nutrition.carbs, after.nutrition.carbs, " g")
            lines += diffLine("脂肪", before.nutrition.fat, after.nutrition.fat, " g")
        }

        if (after.items.isNotEmpty()) {
            val names = after.items.take(5).joinToString("、") { it.name }
            lines += "分项　" + after.items.size + " 样：" + names +
                (if (after.items.size > 5) " …" else "")
        }
        if (after.advice.isNotBlank() && after.advice != before.advice) {
            lines += ""
            lines += "新建议：" + after.advice
        }

        if (lines.isEmpty()) {
            lines += if (changed) "模型已更新这条记录。" else "模型重新估算后，结论和之前一致。"
        }
        return lines.joinToString("\n")
    }

    private fun diffLine(label: String, before: Double, after: Double, unit: String): String {
        val old = before.roundToInt()
        val new = after.roundToInt()
        val tail = when {
            new > old -> "（+" + (new - old) + "）"
            new < old -> "（" + (new - old) + "）"
            else -> "（不变）"
        }
        return label + "　" + old + " → " + new + unit + tail
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
