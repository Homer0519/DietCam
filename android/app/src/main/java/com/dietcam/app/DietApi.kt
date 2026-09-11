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
class DietApi(private val settings: DietSettings) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
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

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        execute(newRequest(endpoint("/health")).get().build())
    }

    /** 上传照片做分析；note 会作为补充说明一起交给模型。 */
    suspend fun analyze(file: File, note: String): JSONObject = withContext(Dispatchers.IO) {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        if (note.isNotBlank()) {
            form.addFormDataPart("note", note)
        }
        val request = newRequest(endpoint("/analyze")).post(form.build()).build()
        execute(request)
    }

    /** 纯文字记录：不拍照，直接描述吃了什么。 */
    suspend fun analyzeText(text: String): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text)
        val request = newRequest(endpoint("/analyze_text"))
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        execute(request)
    }

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // AstrBot 和插件都会返回 {"message": "..."} 形式的错误信封
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

    companion object {
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
                        "APP 里的『签名密钥』必须与插件配置里的 hmac_secret 完全一致。\n" +
                        "注意区分：AstrBot API Key 管的是能不能进门，签名密钥管的是插件认不认你。"

                e is DietApiException && e.statusCode == 404 ->
                    "地址不对，或插件没加载成功（HTTP 404）。\n\n" +
                        "· 地址应形如 http://你的服务器IP:6185（不要带结尾斜杠）\n" +
                        "· AstrBot 启动日志里应有『[diet] 已注册 6 个 Web API』"

                e is java.net.UnknownHostException ->
                    "找不到这个服务器地址。\n\n检查 IP 有没有写错，以及手机是否联网。"

                e is java.net.ConnectException ->
                    "连不上服务器。\n\n· 确认 AstrBot 正在运行\n· 确认端口（默认 6185）已放行\n" +
                        "· 若用 https 请确认证书有效"

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
