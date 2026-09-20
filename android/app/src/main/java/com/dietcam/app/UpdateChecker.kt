package com.dietcam.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 有新版时带出来的信息。 */
data class UpdateInfo(
    val version: String,
    /** 永远指向最新版 APK 的固定直链。 */
    val apkUrl: String,
    /** 给人看的发布页。 */
    val pageUrl: String,
)

/** 一次检查的结果：有新版 / 已是最新 / 查失败，三种分开。 */
data class UpdateOutcome(
    val info: UpdateInfo? = null,
    /** GitHub 上看到的最新版本号（查到了就有）。 */
    val latest: String? = null,
    /** 失败原因；null 表示这次查成功了。 */
    val error: String? = null,
)

/**
 * 检查 GitHub 上有没有新版本 —— **只用一条路，不碰 API**。
 *
 * `github.com/<owner>/<repo>/releases/latest` 会 302 到 `.../tag/vX.Y.Z`，
 * 从最终地址里就能读出最新版本号。一次请求，不需要 token，**也不吃那 60 次/小时的匿名限流**
 * （限流是 REST API 的规则，文件与网页不受它管）。
 *
 * 以前这里先打 API、失败再退网页，两套逻辑两套错误文案 —— 对一个自用/小范围分发的
 * App 来说纯属自找麻烦。现在只有一条路。
 */
object UpdateChecker {

    private const val OWNER = "Homer0519"
    private const val REPO = "DietCam"
    private const val USER_AGENT = "DietCam-Android"

    /** 访问它会 302 到最新版的 tag 页。 */
    private const val WEB_LATEST = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest"

    /**
     * **永远指向最新版 APK** 的固定直链，可以直接发出去 / 印二维码。
     *
     * 原理：GitHub 的 `releases/latest/download/<文件名>` 一直转发到最新那个 release 的同名文件。
     * 所以每次发布只要多传一个固定名 `DietCam.apk`，这条地址就永远不用改。
     */
    const val APK_URL = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest/download/DietCam.apk"

    /** 发布页（浏览器打开，能看历史版本和更新说明）。 */
    const val PAGE_URL = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun check(currentVersion: String): UpdateOutcome = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(WEB_LATEST)
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                val version = versionFromReleaseUrl(response.request.url.toString())
                if (version == null) {
                    UpdateOutcome(error = if (response.isSuccessful) "跳转地址里没有版本号" else "GitHub 返回 HTTP " + response.code)
                } else if (!isNewerVersion(version, currentVersion)) {
                    UpdateOutcome(latest = version)
                } else {
                    UpdateOutcome(
                        info = UpdateInfo(version = version, apkUrl = APK_URL, pageUrl = PAGE_URL),
                        latest = version,
                    )
                }
            }
        }.getOrElse { UpdateOutcome(error = describe(it)) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "网络不通"
        is SocketTimeoutException -> "连接超时"
        else -> e.message ?: "未知错误"
    }
}

/** 把新版 APK 下到系统「下载」目录。 */
object ApkDownloader {

    /**
     * 交给系统的 DownloadManager：自带进度通知，下完点通知就能装，
     * 不需要「安装未知应用」权限，也不用 FileProvider。
     */
    fun start(context: Context, info: UpdateInfo): Boolean {
        val url = info.apkUrl.ifBlank { UpdateChecker.APK_URL }
        return runCatching {
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url))
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
