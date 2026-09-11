package com.dietcam.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class DietApiException(message: String) : Exception(message)

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

    private fun execute(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DietApiException("HTTP " + response.code + "：" + text.take(300))
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw DietApiException("服务器返回的不是合法 JSON：" + text.take(300))
            }
        }
    }

    companion object {
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
