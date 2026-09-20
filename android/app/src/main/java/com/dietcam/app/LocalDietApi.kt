package com.dietcam.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 孤儿照片的年龄保护窗口，与插件 main.py 的 SWEEP_MIN_AGE 对齐。 */
private const val SWEEP_MIN_AGE_MS = 60_000L

/**
 * 从 SSE 的一行里取出增量文本；不是「有内容」的行就返回 null。
 *
 * 抽成纯函数是为了能在 JVM 自检里直接验证 —— 这里踩过一个很坑的雷：
 * 推理类模型常先发 `{"choices":[{"delta":{"content":null,"reasoning_content":"…"}}]}`，
 * 而 org.json 的 `optString("content")` 遇到 JSON null 返回的是**字符串 "null"**（不是空串），
 * 于是界面上会滚出一整屏 "null"。真机截图就是这么来的。
 */
internal fun parseStreamLine(line: String): String? {
    if (line.isBlank() || !line.startsWith("data:")) return null
    val chunk = line.substring(5).trim()
    if (chunk.isEmpty() || chunk == "[DONE]") return null
    val obj = runCatching { JSONObject(chunk) }.getOrNull() ?: return null
    val choices = obj.optJSONArray("choices") ?: return null
    if (choices.length() == 0) return null
    val delta = choices.optJSONObject(0)?.optJSONObject("delta") ?: return null
    if (delta.isNull("content")) return null
    val piece = delta.optString("content", "")
    return piece.takeIf { it.isNotEmpty() && it != "null" }
}

/** 有的服务端不认 stream=true，直接回一个完整的 chat.completion —— 从原始响应体里兜一次。 */
internal fun parseWholeBody(raw: String): String? {
    val choices = runCatching { JSONObject(raw).optJSONArray("choices") }.getOrNull() ?: return null
    val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
    if (message.isNull("content")) return null
    val text = message.optString("content", "")
    return text.takeIf { it.isNotEmpty() && it != "null" }
}

/**
 * 把 [updates] 合并进当前生效的目标 [base]，只覆盖调用方真正传进来的键。
 *
 * 故意写成纯 Kotlin、不碰 JSONObject —— 这样 JVM 单元测试可以直接验证它。
 * 以前这里是「新建一个 JSONObject 整份替换 targets」，在手动目标界面上只填热量
 * 就会把蛋白/碳水/脂肪写成 0，首页的 MacroBar 随即变成 0 / 0g。
 */
internal fun mergeTargets(
    base: Map<String, Double>,
    updates: Map<String, Double>,
): Map<String, Double> {
    val merged = base.toMutableMap()
    updates.forEach { (key, value) -> merged[key] = Math.round(value * 10.0) / 10.0 }
    return merged
}

/**
 * 本地模式：完全不依赖 AstrBot。
 *
 * 照片与记录都放在 App 私有目录里，分析直接调用 OpenAI 兼容的视觉模型接口。
 * 返回的 JSON 结构与 astrbot_plugin_diet 的 Web API 一致，
 * 所以 JsonParse / ViewModel / 各个页面都不需要区分后端是谁。
 *
 * 目录结构（与插件保持一致，方便日后搬家）：
 *
 *     files/diet/records/2026-09-15.jsonl      一天一行一条 JSON
 *     files/diet/photos/2026-09-15/130712_a1b2c3d4.jpg
 *     files/diet/state.json                    目标与身体档案
 */
class LocalDietApi(
    private val context: Context,
    private val settings: DietSettings,
) : DietBackend {

    // ------------------------------------------------------------ 存储

    private val root: File get() = File(context.filesDir, "diet")
    private val recordDir: File get() = File(root, "records")
    private val photoDir: File get() = File(root, "photos")
    private val stateFile: File get() = File(root, "state.json")

    private fun recordFile(day: String) = File(recordDir, day + ".jsonl")

    private fun photosOf(day: String): File = File(photoDir, day)

    private fun calendarOf(): Calendar = Calendar.getInstance()

    private fun dateOf(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

    private fun timeOf(cal: Calendar): String =
        SimpleDateFormat("HH:mm", Locale.US).format(cal.time)

    private fun loadRecords(day: String): MutableList<JSONObject> {
        val file = recordFile(day)
        val out = mutableListOf<JSONObject>()
        if (!file.isFile) return out
        file.forEachLine { line ->
            val text = line.trim()
            if (text.isEmpty()) return@forEachLine
            runCatching { JSONObject(text) }.getOrNull()?.let { out.add(it) }
        }
        return out
    }

    /**
     * 先写 .tmp 再 rename。
     *
     * 直接截断重写的话，写到一半被杀（断电、被系统清理）当天的记录就整份没了 ——
     * records/<day>.jsonl 是唯一的真相来源。插件侧同样改成了原子写。
     */
    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // 少数文件系统上 renameTo 不覆盖已存在的目标，退化成直接写
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun writeRecords(day: String, records: List<JSONObject>) {
        val file = recordFile(day)
        if (records.isEmpty()) {
            file.delete()
            return
        }
        writeAtomically(file, records.joinToString("") { it.toString() + "\n" })
    }

    private fun appendRecord(day: String, record: JSONObject) {
        val file = recordFile(day)
        file.parentFile?.mkdirs()
        file.appendText(record.toString() + "\n")
    }

    private fun loadState(): JSONObject {
        if (!stateFile.isFile) return JSONObject()
        return runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
    }

    private fun saveState(state: JSONObject) {
        root.mkdirs()
        writeAtomically(stateFile, state.toString())
    }

    private fun allDays(): List<String> {
        val days = mutableSetOf<String>()
        recordDir.listFiles()?.forEach { if (it.name.endsWith(".jsonl")) days.add(it.name.removeSuffix(".jsonl")) }
        photoDir.listFiles()?.forEach { if (it.isDirectory) days.add(it.name) }
        return days.sorted()
    }

    // -------------------------------------------------------- 目标与档案

    private fun defaultProfile(): JSONObject = JSONObject()
        .put("height_cm", 170.0)
        .put("weight_kg", 65.0)
        .put("age", 30)
        .put("sex", "male")
        .put("activity", "light")
        .put("goal", "maintain")

    private fun profileObject(): JSONObject {
        val saved = loadState().optJSONObject("profile") ?: JSONObject()
        val merged = defaultProfile()
        saved.keys().forEach { key -> merged.put(key, saved.get(key)) }
        return merged
    }

    /** 与插件里的 Mifflin-St Jeor 完全一致，两边算出来的目标不会打架。 */
    private fun computeTargets(profile: JSONObject): JSONObject {
        val height = profile.optDouble("height_cm", 170.0)
        val weight = profile.optDouble("weight_kg", 65.0)
        val age = profile.optDouble("age", 30.0)
        val male = profile.optString("sex", "male") != "female"

        val bmr = 10.0 * weight + 6.25 * height - 5.0 * age + (if (male) 5.0 else -161.0)
        val activity = ACTIVITY_FACTORS[profile.optString("activity", "light")] ?: 1.375
        val goal = GOAL_FACTORS[profile.optString("goal", "maintain")] ?: 1.0

        val kcal = maxOf(800.0, bmr * activity * goal)
        val protein = maxOf(1.2 * weight, kcal * 0.25 / 4.0)
        val fat = kcal * 0.25 / 9.0
        val carbs = maxOf(0.0, (kcal - protein * 4.0 - fat * 9.0) / 4.0)

        return JSONObject()
            .put("calories_kcal", round1(kcal))
            .put("protein_g", round1(protein))
            .put("carbs_g", round1(carbs))
            .put("fat_g", round1(fat))
    }

    private fun targetsOf(): JSONObject {
        val state = loadState()
        val mode = state.optString("targets_mode", "auto")
        val manual = state.optJSONObject("targets")
        if (mode == "manual" && manual != null) return manual
        return computeTargets(profileObject())
    }

    private fun totals(records: List<JSONObject>): JSONObject {
        var kcal = 0.0
        var protein = 0.0
        var carbs = 0.0
        var fat = 0.0
        records.forEach { rec ->
            if (!rec.optBoolean("is_food", true)) return@forEach
            kcal += rec.optDouble("calories_kcal", 0.0)
            protein += rec.optDouble("protein_g", 0.0)
            carbs += rec.optDouble("carbs_g", 0.0)
            fat += rec.optDouble("fat_g", 0.0)
        }
        return JSONObject()
            .put("calories_kcal", round1(kcal))
            .put("protein_g", round1(protein))
            .put("carbs_g", round1(carbs))
            .put("fat_g", round1(fat))
    }

    // ------------------------------------------------------------ 接口

    /**
     * 本地模式下照片本来就存在手机里（`files/diet/photos/<日期>/`），
     * 不存在「打开记录详情还要再拉一次」的问题，所以这里什么都不做 ——
     * 只是为了满足两个后端一致的接口。
     */
    override suspend fun cachePhoto(date: String, name: String, bytes: ByteArray) = Unit

    override suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        // 本地模式的「测试连接」不联网，只回报数据目录与能力开关 ——
        // 以前这里返回空对象，那一行永远是「数据目录：?」。
        JSONObject()
            .put("ok", true)
            .put("plugin", "dietcam-local")
            .put("local", true)
            .put("version", "本地模式")
            .put("data_dir", root.absolutePath)
            .put(
                "features",
                JSONObject()
                    .put("stream", true).put("summary", true).put("targets", true)
                    .put("profile", true).put("record_edit", true).put("record_delete", true)
                    .put("record_reanalyze", true).put("calendar", true).put("history", true)
                    .put("text", true).put("archive", false),
            )
            .put("targets", targetsOf())
    }

    override suspend fun summary(date: String?): JSONObject = withContext(Dispatchers.IO) {
        val day = date?.takeIf { it.isNotBlank() } ?: dateOf(calendarOf())
        val records = loadRecords(day)
        JSONObject()
            .put("ok", true)
            .put("date", day)
            .put("totals", totals(records))
            .put("targets", targetsOf())
            .put("count", records.size)
            .put("records", JSONArray(records))
    }

    override suspend fun records(date: String): JSONObject = withContext(Dispatchers.IO) {
        val records = loadRecords(date)
        JSONObject()
            .put("ok", true)
            .put("date", date)
            .put("count", records.size)
            .put("totals", totals(records))
            .put("records", JSONArray(records))
    }

    override suspend fun updateTargets(targets: Map<String, Double>): JSONObject =
        withContext(Dispatchers.IO) {
            val state = loadState()
            // 以「当前生效的目标」为底，只覆盖调用方真正传进来的键 —— 与插件版
            // （main.py 的 api_targets 先读 current 再 merge）保持一致。
            val currentTargets = targetsOf()
            val base = buildMap {
                currentTargets.keys().forEach { key -> put(key, currentTargets.optDouble(key, 0.0)) }
            }
            val merged = JSONObject()
            mergeTargets(base, targets).forEach { (key, value) -> merged.put(key, value) }
            state.put("targets", merged)
            state.put("targets_mode", "manual")
            saveState(state)
            JSONObject().put("ok", true).put("targets", merged)
        }

    override suspend fun profile(): JSONObject = withContext(Dispatchers.IO) {
        val state = loadState()
        JSONObject()
            .put("ok", true)
            .put("profile", profileObject())
            .put("suggested", computeTargets(profileObject()))
            .put("targets", targetsOf())
            .put("targets_mode", state.optString("targets_mode", "auto"))
    }

    override suspend fun saveProfile(fields: Map<String, Any>): JSONObject =
        withContext(Dispatchers.IO) {
            val state = loadState()
            val profile = profileObject()

            val ranges = mapOf(
                "height_cm" to (80.0 to 250.0),
                "weight_kg" to (20.0 to 400.0),
                "age" to (5.0 to 120.0),
            )
            ranges.forEach { (key, range) ->
                val raw = fields[key] ?: return@forEach
                val value = (raw as? Number)?.toDouble()
                    ?: raw.toString().trim().toDoubleOrNull()
                    ?: return@forEach
                if (value < range.first || value > range.second) {
                    throw DietApiException(key + " 超出合理范围")
                }
                profile.put(key, if (key == "age") value.toInt() else round1(value))
            }
            (fields["sex"] as? String)?.takeIf { it.isNotBlank() }?.let { profile.put("sex", it) }
            (fields["activity"] as? String)?.takeIf { it.isNotBlank() }?.let { profile.put("activity", it) }
            (fields["goal"] as? String)?.takeIf { it.isNotBlank() }?.let { profile.put("goal", it) }

            state.put("profile", profile)
            state.put("targets_mode", "auto")
            state.remove("targets")
            saveState(state)

            JSONObject()
                .put("ok", true)
                .put("profile", profile)
                .put("suggested", computeTargets(profile))
                .put("targets", computeTargets(profile))
                .put("targets_mode", "auto")
        }

    override suspend fun calendar(month: String): JSONObject = withContext(Dispatchers.IO) {
        val days = JSONObject()
        allDays().filter { it.startsWith(month) }.forEach { day ->
            val records = loadRecords(day)
            if (records.isEmpty()) return@forEach
            val t = totals(records)
            days.put(
                day,
                JSONObject()
                    .put("calories_kcal", t.optDouble("calories_kcal", 0.0))
                    .put("protein_g", t.optDouble("protein_g", 0.0))
                    .put("carbs_g", t.optDouble("carbs_g", 0.0))
                    .put("fat_g", t.optDouble("fat_g", 0.0))
                    .put("count", records.size),
            )
        }
        JSONObject().put("ok", true).put("month", month).put("targets", targetsOf()).put("days", days)
    }

    override suspend fun history(days: Int, end: String?): JSONObject = withContext(Dispatchers.IO) {
        val span = days.coerceIn(1, 365)
        val cal = calendarOf()
        end?.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(text) }.getOrNull()?.let { cal.time = it }
        }
        // 先把 end 记下来：下面这个循环会把 cal 一路往前推
        val endDay = dateOf(cal)
        val wanted = LinkedHashSet<String>()
        for (i in 0 until span) {
            wanted.add(dateOf(cal))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }

        val all = mutableListOf<JSONObject>()
        val perDay = JSONObject()
        wanted.forEach { day ->
            val records = loadRecords(day)
            if (records.isEmpty()) return@forEach
            all.addAll(records)
            val t = totals(records)
            perDay.put(
                day,
                JSONObject()
                    .put("calories_kcal", t.optDouble("calories_kcal", 0.0))
                    .put("protein_g", t.optDouble("protein_g", 0.0))
                    .put("carbs_g", t.optDouble("carbs_g", 0.0))
                    .put("fat_g", t.optDouble("fat_g", 0.0))
                    .put("count", records.size),
            )
        }
        all.sortWith(compareByDescending<JSONObject> { it.optString("date") }.thenByDescending { it.optString("time") })

        // 字段与插件 /history 对齐：少一个字段，上层就会出现「本地有、服务器没有」
        // 那种只在一种模式下发作的怪毛病。
        JSONObject()
            .put("ok", true)
            .put("end", endDay)
            .put("days", span)
            .put("count", all.size)
            .put("targets", targetsOf())
            .put("records", JSONArray(all))
            .put("per_day", perDay)
    }

    override suspend fun updateRecord(date: String, id: String, fields: Map<String, Any>): JSONObject =
        withContext(Dispatchers.IO) {
            val records = loadRecords(date)
            val index = records.indexOfFirst { it.optString("id") == id }
            if (index < 0) throw DietApiException("没有找到这条记录")
            val record = records[index]

            fields.forEach { (key, value) ->
                when (key) {
                    "title", "meal", "advice", "note" -> record.put(key, value.toString().take(300))
                    "calories_kcal", "protein_g", "carbs_g", "fat_g" -> {
                        val number = (value as? Number)?.toDouble() ?: value.toString().toDoubleOrNull()
                        if (number != null && number >= 0 && number < 20000) record.put(key, round1(number))
                    }
                    "is_food" -> record.put("is_food", value as? Boolean ?: (value.toString() == "true"))
                }
            }
            record.put("edited", true)
            record.put("edited_at", System.currentTimeMillis() / 1000)
            records[index] = record
            writeRecords(date, records)
            JSONObject().put("ok", true).put("record", record)
        }

    override suspend fun deleteRecord(date: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val records = loadRecords(date)
        val index = records.indexOfFirst { it.optString("id") == id }
        if (index < 0) {
            sweepOrphans(date)
            return@withContext JSONObject().put("ok", true).put("already_gone", true)
        }
        val removed = records.removeAt(index)
        writeRecords(date, records)

        val photo = removed.optString("photo", "")
        if (photo.isNotEmpty() && records.none { it.optString("photo", "") == photo }) {
            File(photosOf(date), photo).delete()
        }
        sweepOrphans(date)
        JSONObject().put("ok", true).put("removed", removed).put("left", records.size)
    }

    /**
     * 没有记录引用的照片一律收掉（分析失败/中断留下的）。
     *
     * 只删「够旧」的：正在分析中的那张照片此刻还没被任何记录引用，
     * 不加年龄保护就会被并发的删除/编辑顺手误删，随后记录里的 photo 指向空文件。
     * 插件 side 有同样的 60 秒窗口（main.py 的 SWEEP_MIN_AGE），两边口径要对齐。
     */
    private fun sweepOrphans(day: String) {
        val used = loadRecords(day).map { it.optString("photo", "") }.toSet()
        val now = System.currentTimeMillis()
        photosOf(day).listFiles()?.forEach { file ->
            if (!file.isFile || file.name in used) return@forEach
            if (now - file.lastModified() < SWEEP_MIN_AGE_MS) return@forEach
            file.delete()
        }
    }

    override suspend fun reanalyzeRecord(date: String, id: String, instruction: String): JSONObject =
        withContext(Dispatchers.IO) {
            val records = loadRecords(date)
            val index = records.indexOfFirst { it.optString("id") == id }
            if (index < 0) throw DietApiException("没有找到这条记录")
            val old = records[index]

            val note = listOf(old.optString("note", ""), instruction)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "请重新估算这一餐，给出更准确的结果。" }

            val photo = old.optString("photo", "")
            val imageFile = if (photo.isNotEmpty()) File(photosOf(date), photo) else null
            val hasPhoto = imageFile != null && imageFile.isFile
            if (photo.isNotEmpty() && !hasPhoto) throw DietApiException("归档照片已不存在，无法重新分析")

            val now = calendarOf()
            val prompt = buildPrompt(hasPhoto, note, now)
            val raw = callModel(
                if (hasPhoto) encodeImage(imageFile!!) else null,
                prompt,
                onDelta = {},
            )
            val parsed = parseModelJson(raw)

            val updated = JSONObject(old.toString())
            updated.put("title", parsed.optString("title").ifBlank { old.optString("title") }.take(80))
            updated.put("meal", parsed.optString("meal").ifBlank { old.optString("meal") }.take(16))
            updated.put("calories_kcal", round1(parsed.optDouble("calories_kcal", 0.0)))
            updated.put("protein_g", round1(parsed.optDouble("protein_g", 0.0)))
            updated.put("carbs_g", round1(parsed.optDouble("carbs_g", 0.0)))
            updated.put("fat_g", round1(parsed.optDouble("fat_g", 0.0)))
            updated.put("confidence", round1(parsed.optDouble("confidence", 0.0)))
            updated.put("items", parsed.optJSONArray("items") ?: old.optJSONArray("items") ?: JSONArray())
            updated.put("advice", parsed.optString("advice").take(300))
            updated.put("is_food", parsed.optBoolean("is_food", true))
            updated.put("reanalyzed", true)
            updated.put("reanalyzed_at", System.currentTimeMillis() / 1000)

            records[index] = updated
            writeRecords(date, records)
            JSONObject().put("ok", true).put("record", updated)
        }

    override suspend fun photoBytes(date: String, name: String): ByteArray = withContext(Dispatchers.IO) {
        val clean = File(name).name
        val file = File(photosOf(date), clean)
        if (!file.isFile) throw DietApiException("照片不存在")
        file.readBytes()
    }

    // -------------------------------------------------------- 分析流水线

    override suspend fun analyzeStream(file: File, note: String, onDelta: (String) -> Unit): JSONObject =
        withContext(Dispatchers.IO) {
            val now = calendarOf()
            val day = dateOf(now)
            val name = archivePhoto(file, day)
            val raw = try {
                callModel(encodeImage(file), buildPrompt(true, note, now), onDelta)
            } catch (e: Exception) {
                // 没分析成就不留孤儿照片
                File(photosOf(day), name).delete()
                throw e
            }
            val record = buildRecord(day, timeOf(now), name, parseModelJson(raw), "app", note)
            appendRecord(day, record)
            record
        }

    override suspend fun analyzeTextStream(text: String, onDelta: (String) -> Unit): JSONObject =
        withContext(Dispatchers.IO) {
            val now = calendarOf()
            val day = dateOf(now)
            val raw = callModel(null, buildPrompt(false, text, now), onDelta)
            val record = buildRecord(day, timeOf(now), "", parseModelJson(raw), "app-text", text)
            appendRecord(day, record)
            record
        }

    private fun archivePhoto(source: File, day: String): String {
        val dir = photosOf(day)
        dir.mkdirs()
        val stamp = SimpleDateFormat("HHmmss", Locale.US).format(calendarOf().time)
        val name = stamp + "_" + UUID.randomUUID().toString().replace("-", "").take(8) + ".jpg"
        source.copyTo(File(dir, name), overwrite = true)
        return name
    }

    /** 上传前压到长边 1280，既省流量也让模型更容易接受。 */
    private fun encodeImage(file: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw DietApiException("照片读取失败，请重试")
        }
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 1280) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw DietApiException("照片读取失败，请重试")

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun buildPrompt(withPhoto: Boolean, note: String, now: Calendar): String {
        val base = if (withPhoto) PHOTO_PROMPT else TEXT_PROMPT
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(now.time)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now.time)

        val builder = StringBuilder(base)
        builder.append("\n\n当前时间：").append(stamp).append("（").append(week).append("）。")
        builder.append("请据此判断 meal：早餐 05:00-10:00，午餐 10:00-14:00，")
        builder.append("晚餐 17:00-21:00，其余时段算加餐或夜宵；")
        builder.append("如果用户明确说了是哪一餐（例如「早饭」），以用户说的为准。")
        if (note.isNotBlank()) {
            builder.append("\n\n用户的补充说明：").append(note)
        }
        return builder.toString()
    }

    private fun buildRecord(
        day: String,
        time: String,
        photo: String,
        parsed: JSONObject,
        source: String,
        note: String,
    ): JSONObject {
        val isFood = parsed.optBoolean("is_food", true)
        val items = parsed.optJSONArray("items") ?: JSONArray()
        val title = parsed.optString("title").ifBlank {
            if (isFood) "一餐" else parsed.optString("reason").ifBlank { "未识别到食物" }
        }
        return JSONObject()
            .put("id", UUID.randomUUID().toString().replace("-", "").take(12))
            .put("date", day)
            .put("time", time)
            .put("ts", System.currentTimeMillis() / 1000)
            .put("photo", photo)
            .put("source", source)
            .put("note", note.take(200))
            .put("is_food", isFood)
            .put("title", title.take(80))
            .put("meal", parsed.optString("meal").ifBlank { guessMeal(Calendar.getInstance()) }.take(16))
            .put("calories_kcal", round1(parsed.optDouble("calories_kcal", 0.0)))
            .put("protein_g", round1(parsed.optDouble("protein_g", 0.0)))
            .put("carbs_g", round1(parsed.optDouble("carbs_g", 0.0)))
            .put("fat_g", round1(parsed.optDouble("fat_g", 0.0)))
            .put("confidence", round1(parsed.optDouble("confidence", 0.0)))
            .put("items", items)
            .put("advice", parsed.optString("advice").take(300))
    }

    private fun parseModelJson(text: String): JSONObject {
        var cleaned = text.trim()
        if (cleaned.startsWith(FENCE)) cleaned = cleaned.removePrefix(FENCE)
        if (cleaned.endsWith(FENCE)) cleaned = cleaned.removeSuffix(FENCE)
        cleaned = cleaned.trim()
        if (cleaned.startsWith("json", ignoreCase = true)) cleaned = cleaned.substring(4).trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()?.let { return it }
        }
        return JSONObject()
            .put("is_food", true)
            .put("title", "解析失败")
            .put("items", JSONArray())
            .put("advice", cleaned.take(200))
    }

    private fun callModel(
        imageBase64: String?,
        prompt: String,
        onDelta: (String) -> Unit,
    ): String {
        // 每次都清一遍：粘贴来的地址/key 常带换行，会让 OkHttp 直接抛
        // Unexpected char 0x0a in Authorization value
        val base = sanitizeUrl(settings.modelBaseUrl).trimEnd('/')
        val modelName = sanitizeName(settings.modelName)
        if (base.isBlank()) throw DietApiException("请先在设置里填写模型接口地址")
        if (modelName.isBlank()) throw DietApiException("请先在设置里填写模型名称")
        val url = if (base.endsWith("/chat/completions")) base else base + "/chat/completions"

        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        if (imageBase64 != null) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + imageBase64)),
            )
        }
        val payload = JSONObject()
            .put("model", modelName)
            .put("stream", true)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + settings.modelApiKey.trim())
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val text = StringBuilder()
        val rawBody = StringBuilder()
        modelClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = runCatching { response.body?.string() }.getOrNull() ?: ""
                throw DietApiException("模型返回 HTTP " + response.code + "：" + body.take(200))
            }
            val source = response.body?.source() ?: throw DietApiException("模型没有返回内容")
            while (true) {
                val line = source.readUtf8Line() ?: break
                rawBody.append(line).append('\n')
                if (line.startsWith("data:") && line.substring(5).trim() == "[DONE]") break
                val piece = parseStreamLine(line) ?: continue
                text.append(piece)
                onDelta(piece)
            }
        }
        if (text.isEmpty()) {
            // 少数服务端根本不认 stream=true，会回一个完整 JSON —— 再兜一次
            parseWholeBody(rawBody.toString())?.let { whole ->
                onDelta(whole)
                return whole
            }
            throw DietApiException("模型没有返回内容")
        }
        return text.toString()
    }

    private val modelClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)   // 流式：一直读下去
            .build()
    }

    private fun guessMeal(cal: Calendar): String = when (cal.get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "夜宵"
        in 5..9 -> "早餐"
        in 10..13 -> "午餐"
        in 14..16 -> "加餐"
        in 17..20 -> "晚餐"
        else -> "夜宵"
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0

    companion object {
        /**
         * 拉取 OpenAI 兼容接口支持的模型列表（GET /models）。
         *
         * 顺手兼容两种返回格式：标准 OpenAI 的 {"data":[{"id":...}]}，
         * 以及 Ollama 那种 {"models":[{"name":...}]}。
         */
        suspend fun listModels(settings: DietSettings): List<String> = withContext(Dispatchers.IO) {
            val base = sanitizeUrl(settings.modelBaseUrl).trimEnd('/')
            if (base.isBlank()) throw DietApiException("请先填写模型接口地址")
            val url = if (base.endsWith("/models")) base else base + "/models"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + sanitizeKey(settings.modelApiKey))
                .get()
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            client.newCall(request).execute().use { response ->
                val body = runCatching { response.body?.string() }.getOrNull() ?: ""
                if (!response.isSuccessful) {
                    throw DietApiException(
                        "拉取模型列表失败 HTTP " + response.code + "：" + body.take(200),
                    )
                }
                val obj = runCatching { JSONObject(body) }.getOrNull()
                    ?: throw DietApiException("模型列表不是合法的 JSON")

                val out = LinkedHashSet<String>()
                obj.optJSONArray("data")?.let { array ->
                    for (i in 0 until array.length()) {
                        val id = array.optJSONObject(i)?.optString("id", "") ?: ""
                        if (id.isNotBlank()) out.add(id)
                    }
                }
                if (out.isEmpty()) {
                    obj.optJSONArray("models")?.let { array ->
                        for (i in 0 until array.length()) {
                            val item = array.optJSONObject(i)
                            val id = item?.optString("name", "")?.ifBlank { item.optString("model", "") } ?: ""
                            if (id.isNotBlank()) out.add(id)
                        }
                    }
                }
                out.sorted()
            }
        }

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val FENCE = "\u0060\u0060\u0060"

        private val ACTIVITY_FACTORS = mapOf(
            "sedentary" to 1.2,
            "light" to 1.375,
            "moderate" to 1.55,
            "active" to 1.725,
            "very_active" to 1.9,
        )
        private val GOAL_FACTORS = mapOf("lose" to 0.80, "maintain" to 1.0, "gain" to 1.15)

        private val PHOTO_PROMPT = """
            你是一位严谨、友善的专业营养师。请仔细观察这张餐食照片，估算每种食物的份量与营养。

            输出格式严格两行：
            第一行：一句简短的中文观察，40 字以内，说明你看到了什么。
            第二行：一个 JSON 对象（写成一行，不要换行，不要用 Markdown 代码块包裹）。

            JSON 结构如下：
            {"is_food":true,"title":"一句话概括这一餐","meal":"早餐或午餐或晚餐或加餐或夜宵","items":[{"name":"食物名","portion":"约150g","calories_kcal":230,"protein_g":12.5,"carbs_g":30.0,"fat_g":6.0}],"calories_kcal":520,"protein_g":30.0,"carbs_g":60.0,"fat_g":18.0,"confidence":0.8,"advice":"一句简短、具体、友善的建议"}

            要求：
            1. 若图片中没有任何食物，返回 {"is_food": false, "reason": "简短原因"}。
            2. 所有数值都是估算值；calories_kcal / protein_g / carbs_g / fat_g 表示整餐合计。
            3. confidence 是 0 到 1 之间的置信度。
            4. advice 要具体，不要说空话。
            5. 第二行必须是能被 json.loads 直接解析的完整 JSON，前后不要有任何多余字符。
        """.trimIndent()

        private val TEXT_PROMPT = """
            你是一位严谨、友善的专业营养师。用户没有拍照，而是用文字描述了自己吃了什么。请根据这段描述估算份量与营养。

            输出格式严格两行：
            第一行：一句简短的中文观察，40 字以内，指出你如何理解这份描述。
            第二行：一个 JSON 对象（写成一行，不要换行，不要用 Markdown 代码块包裹）。

            JSON 结构如下：
            {"is_food":true,"title":"一句话概括这一餐","meal":"早餐或午餐或晚餐或加餐或夜宵","items":[{"name":"食物名","portion":"约150g","calories_kcal":230,"protein_g":12.5,"carbs_g":30.0,"fat_g":6.0}],"calories_kcal":520,"protein_g":30.0,"carbs_g":60.0,"fat_g":18.0,"confidence":0.6,"advice":"一句简短、具体、友善的建议"}

            要求：
            1. 描述里没有食物的，返回 {"is_food": false, "reason": "简短原因"}。
            2. 用户对份量的描述可能很模糊（例如"一碗面"），按常见份量估算，并在 confidence 里体现不确定性。
            3. 如果描述明显不完整，照样给出估算，但在 advice 里点出缺了什么信息会算得更准。
            4. 第二行必须是能被 json.loads 直接解析的完整 JSON，前后不要有任何多余字符。
        """.trimIndent()
    }
}
