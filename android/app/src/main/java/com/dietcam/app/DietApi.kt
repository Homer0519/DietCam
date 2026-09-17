package com.dietcam.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DietApiException(
    message: String,
    val statusCode: Int = 0,
    val serverMessage: String = "",
) : Exception(message)

/**
 * 与 AstrBot 上的 astrbot_plugin_diet 插件通信。
 *
 * 请求走 AstrBot 的插件扩展路由 /api/v1/plugins/extensions/ 之下，
 * 用 plugin scope 的 API Key 鉴权，另外附带由共享密钥派生的 X-Diet-Token。
 */
class DietApi(
    private val settings: DietSettings,
    /** 归档照片的本地磁盘缓存目录；为 null 则不缓存。 */
    private val diskCache: File? = null,
) : DietBackend {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 流式请求要能一直读下去，读超时设为不限制。 */
    private val streamClient: OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private fun token(): String {
        val exp = System.currentTimeMillis() / 1000L + 300L
        return exp.toString() + "." + hmacSha256Hex(settings.secret, exp.toString())
    }

    private fun endpoint(path: String): String =
        settings.baseUrl.trim().trimEnd('/') +
            "/api/v1/plugins/extensions/astrbot_plugin_diet" + path

    private fun newRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("X-Diet-Token", token())
        val key = settings.apiKey.trim()
        if (key.isNotEmpty()) {
            builder.header("Authorization", "Bearer " + key)
        }
        return builder
    }

    private fun jsonBody(obj: JSONObject) =
        obj.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

    // ------------------------------------------------------------- 基础接口

    override suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        executeSync(newRequest(endpoint("/health")).get().build())
    }

    /** 主页概览：当日合计、每日目标、记录列表。 */
    override suspend fun summary(date: String?): JSONObject = withContext(Dispatchers.IO) {
        val url = if (date.isNullOrBlank()) {
            endpoint("/summary")
        } else {
            endpoint("/summary") + "?date=" + date
        }
        executeSync(newRequest(url).get().build())
    }

    /** 调整每日目标（存在服务端 state.json）。 */
    override suspend fun updateTargets(targets: Map<String, Double>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
        targets.forEach { (k, v) -> body.put(k, v) }
        executeSync(newRequest(endpoint("/targets")).post(jsonBody(body)).build())
    }

    /** 上传照片做分析；note 会作为补充说明一起交给模型。 */
    suspend fun analyze(file: File, note: String): JSONObject = withContext(Dispatchers.IO) {
        executeSync(newRequest(endpoint("/analyze")).post(multipart(file, note)).build())
    }

    /** 纯文字记录：不拍照，直接描述吃了什么。 */
    suspend fun analyzeText(text: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text)
        executeSync(newRequest(endpoint("/analyze_text")).post(jsonBody(body)).build())
    }

    /** 取回归档照片的原始字节。 */
    /**
     * 取归档照片。
     *
     * 照片存在服务器上，但同一张只下一次：拿到之后写进本地磁盘缓存，
     * 以后再显示这条记录就直接读本地，不再消耗流量（也快得多）。
     */
    override suspend fun photoBytes(date: String, name: String): ByteArray = withContext(Dispatchers.IO) {
        val cached = cachedPhoto(date, name)
        if (cached != null && cached.isFile && cached.length() > 0) {
            return@withContext cached.readBytes()
        }
        val url = endpoint("/photo") + "?date=" + date + "&name=" + name
        val bytes = streamClient.newCall(newRequest(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw DietApiException("取图失败 HTTP " + resp.code, resp.code)
            }
            resp.body?.bytes() ?: ByteArray(0)
        }
        if (bytes.isNotEmpty()) {
            runCatching {
                cached?.parentFile?.mkdirs()
                cached?.writeBytes(bytes)
                trimPhotoCache()
            }
        }
        bytes
    }

    /** 缓存只留最近 200 张，免得日积月累把手机塞满。 */
    private fun trimPhotoCache() {
        val dir = diskCache ?: return
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size <= MAX_CACHED_PHOTOS) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_CACHED_PHOTOS)
            .forEach { it.delete() }
    }

    /** 缓存文件路径；未配置缓存目录时返回 null。 */
    private fun cachedPhoto(date: String, name: String): File? {
        val dir = diskCache ?: return null
        if (date.isBlank() || name.isBlank()) return null
        return File(dir, PhotoCache.key(date, name) + ".img")
    }

    /** 清空本地照片缓存（设置页的「清理缓存」用）。 */
    fun clearPhotoCache(): Long {
        val dir = diskCache ?: return 0L
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            freed += file.length()
            file.delete()
        }
        return freed
    }

    override suspend fun records(date: String): JSONObject = withContext(Dispatchers.IO) {
        val url = endpoint("/records") + "?date=" + date
        executeSync(newRequest(url).get().build())
    }

    // --------------------------------------------------------------- 流式

    /**
     * 流式分析照片。
     *
     * 服务端按 SSE 逐步下发模型输出，[onDelta] 会在每收到一段文本时被调用
     * （运行在 IO 线程，调用方自行保证线程安全）。
     * 返回最终解析好的 record。
     */
    override suspend fun analyzeStream(
        file: File,
        note: String,
        onDelta: (String) -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = newRequest(endpoint("/analyze_stream")).post(multipart(file, note)).build()
        try {
            readSse(request, onDelta)
        } catch (e: DietApiException) {
            if (e.serverMessage == NO_STREAM) {
                // 服务端是旧版插件，退回一次性分析
                recordOf(executeSync(newRequest(endpoint("/analyze")).post(multipart(file, note)).build()))
            } else {
                throw e
            }
        }
    }

    /** 流式分析一段文字描述。 */
    override suspend fun analyzeTextStream(
        text: String,
        onDelta: (String) -> Unit,
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text)
        val request = newRequest(endpoint("/analyze_text_stream")).post(jsonBody(body)).build()
        try {
            readSse(request, onDelta)
        } catch (e: DietApiException) {
            if (e.serverMessage == NO_STREAM) {
                recordOf(
                    executeSync(
                        newRequest(endpoint("/analyze_text")).post(jsonBody(body)).build()
                    )
                )
            } else {
                throw e
            }
        }
    }

    /** 服务端返回体里取 record，没有就整体返回。 */
    private fun recordOf(json: JSONObject): JSONObject =
        json.optJSONObject("record") ?: json

    private fun multipart(file: File, note: String): MultipartBody {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        if (note.isNotBlank()) {
            form.addFormDataPart("note", note)
        }
        return form.build()
    }

    /** 读取 SSE 流，直到收到 done 事件。 */
    private fun readSse(request: Request, onDelta: (String) -> Unit): JSONObject {
        streamClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                val serverMsg = try {
                    JSONObject(text).optString("message")
                } catch (e: Exception) {
                    ""
                }
                throw DietApiException("HTTP " + resp.code + "：" + text.take(300), resp.code, serverMsg)
            }
            val source = resp.body?.source()
                ?: throw DietApiException("服务器没有返回内容")
            val contentType = resp.header("Content-Type").orEmpty()
            if (!contentType.contains("event-stream")) {
                // 服务端不支持流式（旧版插件），退化为一次性读取
                throw DietApiException("服务端未启用流式响应", resp.code, NO_STREAM)
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                val obj = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    continue
                }
                when (obj.optString("type")) {
                    "delta" -> onDelta(obj.optString("text"))
                    "done" -> {
                        val record = obj.optJSONObject("record")
                            ?: throw DietApiException("服务端没有返回结果")
                        return record
                    }
                    "error" -> throw DietApiException(
                        obj.optString("message").ifBlank { "分析失败" }
                    )
                }
            }
            throw DietApiException("连接中断，没有收到完整结果")
        }
    }

    // ------------------------------------------------------------ 响应处理

    private fun executeSync(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val serverMsg = try {
                    JSONObject(text).optString("message")
                } catch (e: Exception) {
                    ""
                }
                throw DietApiException(
                    "HTTP " + response.code + "：" + text.take(300),
                    response.code,
                    serverMsg,
                )
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw DietApiException("服务器返回的不是合法 JSON：" + text.take(300))
            }
        }
    }

    // ------------------------------------------------- 档案 / 日历 / 历史

    override suspend fun profile(): JSONObject = withContext(Dispatchers.IO) {
        executeSync(newRequest(endpoint("/profile")).get().build())
    }

    override suspend fun saveProfile(fields: Map<String, Any>): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject()
        fields.forEach { (k, v) -> body.put(k, v) }
        executeSync(newRequest(endpoint("/profile")).post(jsonBody(body)).build())
    }

    override suspend fun calendar(month: String): JSONObject = withContext(Dispatchers.IO) {
        executeSync(newRequest(endpoint("/calendar") + "?month=" + month).get().build())
    }

    override suspend fun history(days: Int, end: String?): JSONObject = withContext(Dispatchers.IO) {
        val url = endpoint("/history") + "?days=" + days +
            if (end.isNullOrBlank()) "" else "&end=" + end
        executeSync(newRequest(url).get().build())
    }

    // ------------------------------------------------------ 单条记录操作

    override suspend fun updateRecord(date: String, id: String, fields: Map<String, Any>): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("date", date).put("id", id)
            fields.forEach { (k, v) -> body.put(k, v) }
            executeSync(newRequest(endpoint("/record/update")).post(jsonBody(body)).build())
        }

    override suspend fun deleteRecord(date: String, id: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("date", date).put("id", id)
        executeSync(newRequest(endpoint("/record/delete")).post(jsonBody(body)).build())
    }

    override suspend fun reanalyzeRecord(date: String, id: String, instruction: String): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("date", date).put("id", id).put("instruction", instruction)
            executeSync(newRequest(endpoint("/record/reanalyze")).post(jsonBody(body)).build())
        }

    companion object {
        /** 内部标记：服务端不支持流式，调用方应回退到一次性接口。 */
        const val NO_STREAM = "__NO_STREAM__"

        /** 本地图片缓存上限（张）。 */
        private const val MAX_CACHED_PHOTOS = 200

        /** 把底层异常翻译成用户能照着排查的中文提示。 */
        fun friendlyMessage(e: Throwable): String {
            val msg = e.message ?: e.toString()
            return when {
                e is DietApiException && e.statusCode == 401 ->
                    "AstrBot 拒绝了这次请求（HTTP 401）。\n\n" +
                        "说明『AstrBot API Key』不对或已过期。\n" +
                        "请到 AstrBot 网页端 → 设置 → API Key，重新生成一个（勾选 plugin 权限）。"

                e is DietApiException && e.statusCode == 403 ->
                    "AstrBot 拒绝了这次请求（HTTP 403）。\n\n" +
                        "这个 API Key 没有 plugin 权限。\n" +
                        "请新建一个 Key，在权限里勾上 plugin。"

                e is DietApiException && e.serverMessage.contains("unauthorized", true) ->
                    "签名密钥不对（插件返回 unauthorized）。\n\n" +
                        "APP 里的『签名密钥』必须与插件配置里的 hmac_secret 完全一致。"

                e is DietApiException && e.statusCode == 404 ->
                    "地址不对，或插件没加载成功（HTTP 404）。\n\n" +
                        "· 地址应形如 http://你的服务器IP:6185（不要带结尾斜杠）\n" +
                        "· AstrBot 启动日志里应有『[diet] 已注册 N 个 Web API』"

                e is java.net.UnknownHostException ->
                    "找不到这个服务器地址。\n\n检查 IP 有没有写错，以及手机是否联网。"

                e is java.net.ConnectException ->
                    "连不上服务器。\n\n· 确认 AstrBot 正在运行\n· 确认端口（默认 6185）已放行"

                e is java.net.SocketTimeoutException ->
                    "连接超时。\n\n检查网络，或确认服务器没有把该端口限制到特定 IP。"

                else -> msg
            }
        }

        fun hmacSha256Hex(secret: String, message: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            return sb.toString()
        }
    }
}