package com.dietcam.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 一次更新检查的结果。 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/**
 * 从 GitHub Release 上查有没有新版本。
 *
 * 前提是仓库公开：私有仓库的 releases 接口要带 token，而 token 不能塞进 APK 里。
 *
 * 设计上刻意「怂」：任何一步失败都返回 null，绝不抛给调用方 ——
 * 更新检查不该影响正常记账。
 */
object UpdateChecker {

    private const val OWNER = "Homer0519"
    private const val REPO = "DietCam"
    private const val LATEST_URL = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 有新版才返回 [UpdateInfo]，否则（或出错）返回 null。 */
    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "DietCam-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                val json = JSONObject(body)
                val version = json.optString("tag_name").trim().trimStart('v', 'V')
                if (version.isBlank() || !isNewerVersion(version, currentVersion)) return@use null

                var apkUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val item = assets.optJSONObject(i) ?: continue
                        if (item.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = item.optString("browser_download_url")
                            break
                        }
                    }
                }
                UpdateInfo(
                    version = version,
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                )
            }
        }.getOrNull()
    }

}

/** 把新版 APK 下到系统「下载」目录。 */
object ApkDownloader {

    /**
     * 交给系统的 DownloadManager：它自带进度通知，下完点通知就能装，
     * 不需要我们申请「安装未知应用」权限，也不用 FileProvider。
     * 本方法只负责入队，不关心结果。
     */
    fun start(context: Context, info: UpdateInfo): Boolean {
        if (info.apkUrl.isBlank()) return false
        return runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("DietCam " + info.version)
                .setDescription("正在下载新版本")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "DietCam-" + info.version + ".apk",
                )
            manager.enqueue(request)
            true
        }.getOrDefault(false)
    }
}
