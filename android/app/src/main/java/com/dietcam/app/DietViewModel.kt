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

/** 拉取模型列表的结果。 */
data class ModelPickState(
    val loading: Boolean = false,
    val options: List<String> = emptyList(),
    val error: String? = null,
)

/** 日历里某一天的明细。 */
data class DayRecords(
    val date: String,
    val records: List<MealRecord>,
    val loading: Boolean = true,
    val error: String? = null,
)

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

    fun saveSettings(settings: DietSettings) {
        store.baseUrl = settings.baseUrl
        store.apiKey = settings.apiKey
        store.secret = settings.secret
        store.localMode = settings.localMode
        store.modelBaseUrl = settings.modelBaseUrl
        store.modelApiKey = settings.modelApiKey
        store.modelName = settings.modelName
        _configVersion.value = _configVersion.value + 1
        refreshHome()
        refreshProfile()
        // 第一次配置好之后，引导填一次身高体重（只弹一次）——
        // 不填的话首页的「今日目标」永远是 0，新用户会以为是坏的。
        if (store.isConfigured() && !store.profilePrompted) {
            _askProfile.value = true
        }
    }

    private val _askProfile = MutableStateFlow(false)
    val askProfile: StateFlow<Boolean> = _askProfile.asStateFlow()

    /** 引导弹窗关掉了（存了或点了以后再说）：记为已引导，不再弹。 */
    fun dismissAskProfile() {
        _askProfile.value = false
        store.profilePrompted = true
    }

    // ---------------------------------------------------- 本地模式：模型列表

    private val _models = MutableStateFlow<ModelPickState?>(null)
    val models: StateFlow<ModelPickState?> = _models.asStateFlow()

    /** 拉取模型接口支持的模型名，省得手打拼错。 */
    fun fetchModels(settings: DietSettings) {
        _models.value = ModelPickState(loading = true)
        viewModelScope.launch {
            try {
                val list = LocalDietApi.listModels(settings)
                _models.value = if (list.isEmpty()) {
                    ModelPickState(loading = false, error = "接口没有返回任何模型，请手动填写")
                } else {
                    ModelPickState(loading = false, options = list)
                }
            } catch (e: Exception) {
                _models.value = ModelPickState(loading = false, error = DietApi.friendlyMessage(e))
            }
        }
    }

    fun closeModels() {
        _models.value = null
    }

    /** 归档照片的字节；远程模式下这里会命中本地磁盘缓存。 */
    suspend fun photoBytes(date: String, name: String): ByteArray =
        dietBackend(getApplication<Application>(), store.snapshot()).photoBytes(date, name)

    /** 本地模式与服务器模式共用的「配置好了没」判断。 */
    private fun notReadyMessage(): String =
        if (store.snapshot().localMode) "请先在设置里填写模型接口地址与模型名称"
        else "请先在设置里填写服务器地址与密钥"

    fun dismissNotice() {
        _notice.value = null
    }

    // ---------------------------------------------------------- 版本更新

    private val _update = MutableStateFlow<UpdateInfo?>(null)
    val update: StateFlow<UpdateInfo?> = _update.asStateFlow()

    /** 启动时自动查一次，但一天最多一次 —— 更新提示不该天天糊在脸上。 */
    fun checkUpdateIfDue() {
        val dayMs = 24L * 60 * 60 * 1000
        if (System.currentTimeMillis() - store.lastUpdateCheck < dayMs) return
        checkUpdate(manual = false)
    }

    /**
     * 查 GitHub Release 有没有新版。
     * [manual] 为 true（用户主动点「检查更新」）时，没有新版也要给个回执，
     * 否则点一下什么都没发生，又会变成「按了没反应」。
     */
    fun checkUpdate(manual: Boolean) {
        val current = BuildConfig.VERSION_NAME
        viewModelScope.launch {
            val outcome = UpdateChecker.check(current)

            // 只有**真的查成功**才记账。以前失败也写时间戳，于是网络抖一下或者被
            // 限流一次，接下来整整 24 小时都不再检查 —— 用户看到的就是「永远没有更新」。
            if (outcome.error == null) {
                store.lastUpdateCheck = System.currentTimeMillis()
            }

            when {
                outcome.info != null -> _update.value = outcome.info
                !manual -> Unit
                outcome.error != null -> _notice.value = Notice("检查更新失败", outcome.error)
                else -> _notice.value = Notice(
                    "已是最新版本",
                    "当前 " + current + "，GitHub 上最新是 " + (outcome.latest ?: "?") + "。",
                )
            }
        }
    }

    fun dismissUpdate() {
        _update.value = null
    }

    /** 把新版 APK 交给系统下载器，下完点通知栏那一项即可安装。 */
    fun downloadUpdate() {
        val info = _update.value ?: return
        _update.value = null
        val started = info.apkUrl.isNotBlank() && ApkDownloader.start(getApplication(), info)
        _notice.value = if (started) {
            Notice(
                "已开始下载",
                "DietCam " + info.version + " 正在后台下载（存到「下载」目录）。\n" +
                    "下完点通知栏里的那一项就能安装。",
            )
        } else {
            Notice("请手动下载", "打开发布页复制链接下载：\n" + info.pageUrl)
        }
    }

    fun testConnection(settings: DietSettings = store.snapshot()) {
        val current = settings
        val missing = mutableListOf<String>()
        if (current.localMode) {
            if (current.modelBaseUrl.isBlank()) missing.add("模型接口地址")
            if (current.modelName.isBlank()) missing.add("模型名称")
        } else {
            if (current.baseUrl.isBlank()) missing.add("服务器地址")
            if (current.apiKey.isBlank()) missing.add("AstrBot API Key")
            if (current.secret.isBlank()) missing.add("签名密钥")
        }
        if (missing.isNotEmpty()) {
            _notice.value = Notice("还差几项没填", "请先填写：" + missing.joinToString("、"))
            return
        }
        viewModelScope.launch {
            try {
                val json = dietBackend(getApplication<Application>(), current).health()
                _notice.value = if (current.localMode) {
                    Notice(
                        "本地模式已就绪 ✅",
                        buildString {
                            append("模型：").append(current.modelName)
                            append("\n接口：").append(current.modelBaseUrl)
                            append("\n数据目录：").append(json.optString("data_dir", "?"))
                            append("\n\n不需要 AstrBot —— 照片和分析结果都存在这台手机上。")
                        },
                    )
                } else {
                    val features = json.optJSONObject("features")
                    val stream = features?.optBoolean("stream", false) ?: false
                    Notice(
                        "连接成功 ✅",
                        buildString {
                            append("插件版本：").append(json.optString("version", "?"))
                            append("\n流式分析：").append(if (stream) "已启用" else "未启用（将自动降级）")
                            append("\n数据目录：").append(json.optString("data_dir", "?"))
                            append("\n\n两把钥匙都对上了，可以开始记录。")
                        },
                    )
                }
            } catch (e: Exception) {
                _notice.value = Notice("连接失败", DietApi.friendlyMessage(e))
            }
        }
    }

    // -------------------------------------------------------------- 主页

    fun refreshHome() {
        val current = store.snapshot()
        if (!store.isConfigured()) {
            _home.value = HomeUiState(loading = false, error = notReadyMessage())
            return
        }
        viewModelScope.launch {
            _home.value = _home.value.copy(refreshing = _home.value.summary != null, error = null)
            try {
                val json = dietBackend(getApplication<Application>(), current).summary()
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
        if (!store.isConfigured()) {
            _notice.value = Notice("还没配置好", notReadyMessage())
            return
        }
        if (targets.isEmpty()) {
            _notice.value = Notice("没填写目标", "四个框里至少填一个再保存。")
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val backend = dietBackend(getApplication<Application>(), current)
                backend.updateTargets(targets)

                // 存完必须把**档案**也重新读一遍。
                // 以前这里只刷新了首页：「每日目标」卡片读的是 _profile，
                // 而 _profile 还停在保存前那份 —— 于是刚写进去的手动热量
                // 被「按档案自动推算」的旧值盖着显示，用户看到的就是
                // 「改不了热量，只能按推荐的走」。
                val reread = runCatching { backend.profile() }.getOrNull()
                if (reread != null) _profile.value = ProfileParse.info(reread)
                val shown = _profile.value

                _notice.value = Notice(
                    "手动目标已保存",
                    buildString {
                        val t = shown?.targets
                        if (t != null) {
                            append("每日 " + t.kcal.roundToInt() + " 千卡\n")
                            append("蛋白 " + t.protein.roundToInt() + " g · 碳水 " +
                                t.carbs.roundToInt() + " g · 脂肪 " + t.fat.roundToInt() + " g\n")
                        }
                        if (shown?.mode != "manual") {
                            append("\n⚠️ 保存后模式不是「手动」，可能没写进去 —— 请点设置里的自检。")
                        } else {
                            append("\n这些是手动值；之后再改「身体档案」并保存，会重新按公式推算。")
                        }
                    },
                )
                refreshHome()
            } catch (e: Exception) {
                _notice.value = Notice("保存目标失败", DietApi.friendlyMessage(e))
            } finally {
                _busy.value = false
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
        if (!store.isConfigured()) {
            _capture.value = CaptureUiState.Failed(notReadyMessage())
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
        if (!store.isConfigured()) {
            _capture.value = CaptureUiState.Failed(notReadyMessage())
            return
        }
        startAnalyze(null, desc) { api, onDelta ->
            api.analyzeTextStream(desc, onDelta)
        }
    }

    private fun startAnalyze(
        file: File?,
        note: String,
        call: suspend (DietBackend, (String) -> Unit) -> org.json.JSONObject,
    ) {
        analyzeJob?.cancel()
        val api = dietBackend(getApplication<Application>(), store.snapshot())
        val buffer = StringBuilder()
        _capture.value = CaptureUiState.Analyzing("")
        analyzeJob = viewModelScope.launch {
            try {
                val record = call(api) { piece ->
                    buffer.append(piece)
                    _capture.value = CaptureUiState.Analyzing(buffer.toString())
                }
                // 刚拍的这张顺手塞进本地缓存：等会儿在记录详情里点开看大图时，
                // 直接命中缓存，不用为同一张图再往服务器拉一次。
                if (file != null) {
                    runCatching {
                        val date = record.optString("date")
                        val name = record.optString("photo")
                        if (date.isNotBlank() && name.isNotBlank()) {
                            api.cachePhoto(date, name, file.readBytes())
                        }
                    }
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

    // ------------------------------------------------------- 日历某天明细

    private val _dayRecords = MutableStateFlow<DayRecords?>(null)
    val dayRecords: StateFlow<DayRecords?> = _dayRecords.asStateFlow()

    /** 点开日历里的某一天，把当天吃了什么拉出来。 */
    fun loadDay(date: String) {
        _dayRecords.value = DayRecords(date, emptyList(), loading = true)
        viewModelScope.launch {
            try {
                val json = dietBackend(getApplication(), store.snapshot()).records(date)
                _dayRecords.value = DayRecords(
                    date = date,
                    records = JsonParse.summary(json).records,
                    loading = false,
                )
            } catch (e: Exception) {
                _dayRecords.value = DayRecords(
                    date = date,
                    records = emptyList(),
                    loading = false,
                    error = DietApi.friendlyMessage(e),
                )
            }
        }
    }

    fun closeDay() {
        _dayRecords.value = null
    }

    // ------------------------------------------------- 档案 / 日历 / 历史

    private val _profile = MutableStateFlow<ProfileInfo?>(null)
    val profile: StateFlow<ProfileInfo?> = _profile.asStateFlow()

    /** 服务端插件的实际版本（连上 AstrBot 时才拿得到），供「关于」显示，避免写死。 */
    private val _serverVersion = MutableStateFlow<String?>(null)
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    private val _calendar = MutableStateFlow<CalendarMonth?>(null)
    val calendar: StateFlow<CalendarMonth?> = _calendar.asStateFlow()

    private val _history = MutableStateFlow<List<MealRecord>>(emptyList())
    val history: StateFlow<List<MealRecord>> = _history.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refreshProfile() {
        val current = store.snapshot()
        // 不能用 baseUrl/secret 判空：本地模式下这两个字段本来就空着，
        // 那样档案页会永远加载不出来。一律以 store.isConfigured() 为准。
        if (!store.isConfigured()) return
        viewModelScope.launch {
            val backend = dietBackend(getApplication<Application>(), current)
            runCatching { backend.profile() }
                .onSuccess { _profile.value = ProfileParse.info(it) }
                .onFailure { _notice.value = Notice("读取档案失败", DietApi.friendlyMessage(it)) }
            if (!current.localMode) {
                // 顺手把服务端插件版本带回来，「关于」里就不用写死版本号了
                runCatching { backend.health() }.onSuccess { json ->
                    _serverVersion.value = json.optString("version").takeIf { v -> v.isNotBlank() }
                }
            }
        }
    }

    fun saveProfile(profile: BodyProfile, onDone: () -> Unit = {}) {
        val current = store.snapshot()
        if (!store.isConfigured()) {
            // 以前这里是静默 return（而且排在 _busy = true 前面），
            // 本地模式下点「保存并重算目标」连转圈都没有，看起来像按钮坏了。
            _notice.value = Notice("还没配置好", notReadyMessage())
            return
        }
        _busy.value = true
        viewModelScope.launch {
            try {
                val backend = dietBackend(getApplication<Application>(), current)
                val json = backend.saveProfile(
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

                // 回读一次。以前只看「写接口返回了什么」——万一没落盘，
                // 弹窗照样说成功，用户只看到「填了没用、按了没反应」。
                // 现在把真正读回来的值摆出来，写没写进去一目了然。
                val reread = runCatching { backend.profile() }.getOrNull()
                    ?.let { ProfileParse.info(it) }
                if (reread != null) _profile.value = reread
                val shown = reread ?: ProfileParse.info(json)
                val stored = shown.profile
                val t = shown.targets

                _notice.value = Notice(
                    "档案已保存 ✅",
                    buildString {
                        append("已存入：" + stored.heightCm.roundToInt() + " cm / " +
                            stored.weightKg.roundToInt() + " kg / " + stored.age + " 岁\n")
                        append("每日目标：" + t.kcal.roundToInt() + " 千卡\n")
                        append("蛋白 " + t.protein.roundToInt() + " g · 碳水 " +
                            t.carbs.roundToInt() + " g · 脂肪 " + t.fat.roundToInt() + " g\n")
                        if (t.kcal <= 0.0) {
                            append("\n⚠️ 目标还是 0，说明没算出来，请点「本地模式自检」看看。")
                        }
                    },
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

    /**
     * 本地模式自检：把「设置存了没 / 后端能不能起来 / 档案和目标是什么」一次报清楚。
     *
     * 加它的原因很直接 —— 本地模式只有在真机上才能点，出问题时看不到任何内部状态，
     * 只能来回猜。这个按钮不改任何数据，只读。
     */
    fun localSelfCheck() {
        val current = store.snapshot()
        _notice.value = Notice("自检中…", "正在读取…")
        viewModelScope.launch {
            val lines = mutableListOf<String>()
            lines += "运行方式：" + (if (current.localMode) "本地模式（不依赖 AstrBot）" else "连 AstrBot")
            lines += "配置是否齐全：" + if (store.isConfigured()) "是" else "否 ← 这一项为否则按钮会没反应"
            if (current.localMode) {
                lines += "模型地址：" + current.modelBaseUrl.ifBlank { "(空)" }
                lines += "模型名称：" + current.modelName.ifBlank { "(空)" }
                lines += "模型 Key：" + if (current.modelApiKey.isBlank()) "(空)" else "已填"
            } else {
                lines += "服务器地址：" + current.baseUrl.ifBlank { "(空)" }
                lines += "签名密钥：" + if (current.secret.isBlank()) "(空)" else "已填"
            }
            try {
                val backend = dietBackend(getApplication<Application>(), current)
                val health = backend.health()
                lines += "数据目录：" + health.optString("data_dir", "?")
                val info = ProfileParse.info(backend.profile())
                lines += "已存档案：" + info.profile.heightCm.roundToInt() + " cm / " +
                    info.profile.weightKg.roundToInt() + " kg / " + info.profile.age + " 岁"
                lines += "目标来源：" + info.mode
                lines += "当前目标：" + info.targets.kcal.roundToInt() + " 千卡（蛋白 " +
                    info.targets.protein.roundToInt() + " g）"
                val today = backend.summary(null)
                lines += "今日汇总：" + today.optJSONObject("totals")
                    ?.optDouble("calories_kcal", 0.0)?.toInt() + " 千卡 / " +
                    today.optInt("count") + " 条"
            } catch (e: Exception) {
                lines += "读取失败：" + DietApi.friendlyMessage(e)
            }
            _notice.value = Notice("本地模式自检", lines.joinToString("\n"))
        }
    }

    fun refreshCalendar(month: String) {
        val current = store.snapshot()
        if (!store.isConfigured()) return
        viewModelScope.launch {
            runCatching { dietBackend(getApplication<Application>(), current).calendar(month) }
                .onSuccess { _calendar.value = ProfileParse.calendar(it) }
                .onFailure { _notice.value = Notice("读取日历失败", DietApi.friendlyMessage(it)) }
        }
    }

    fun refreshHistory(days: Int = 30) {
        val current = store.snapshot()
        if (!store.isConfigured()) return
        viewModelScope.launch {
            runCatching { dietBackend(getApplication<Application>(), current).history(days) }
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
                dietBackend(getApplication<Application>(), current).updateRecord(date, record.id, fields)
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
                dietBackend(getApplication<Application>(), current).deleteRecord(date, record.id)
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
                val resp = dietBackend(getApplication<Application>(), current).reanalyzeRecord(date, record.id, instruction)
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