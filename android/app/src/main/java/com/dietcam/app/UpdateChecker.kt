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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** 一次更新检查的结果。 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/**
 * 一次检查的完整结果 —— 关键是**区分「确实没有新版」和「根本没查成」**。
 *
 * 以前只返回「有新版 / null」，于是网络不通、被限流、仓库没公开，全都表现成
 * 「已是最新版本」。用户看到的提示是错的，我也没法从截图里判断问题在哪。
 */
data class UpdateOutcome(
    /** 有新版时才非空。 */
    val info: UpdateInfo? = null,
    /** GitHub 上看到的最新版本号（查到过就有值）。 */
    val latest: String? = null,
    /** 查询失败的原因；为 null 表示这次查询是成功的。 */
    val error: String? = null,
)

/**
 * 从 GitHub Release 上查有没有新版本。
 *
 * 前提是仓库公开：私有仓库的 releases 接口要带 token，而 token 不能塞进 APK 里。
 *
 * 两条路：
 *  1. api.github.com 的 releases/latest —— 信息最全（更新说明、APK 直链），
 *     但有 60 次/小时的匿名限流；
 *  2. github.com/.../releases/latest 的 302 —— 不限额流，但只有版本号。
 * 第一条不通就退到第二条：国内网络/限流下第二条往往还能用。
 */
object UpdateChecker {

    private const val OWNER = "Homer0519"
    private const val REPO = "DietCam"
    private const val USER_AGENT = "DietCam-Android"
    private const val API_LATEST = "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest"
    private const val WEB_LATEST = "https://github.com/" + OWNER + "/" + REPO + "/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun check(currentVersion: String): UpdateOutcome = withContext(Dispatchers.IO) {
        val first = runCatching { viaApi(currentVersion) }
            .getOrElse { UpdateOutcome(error = describe(it)) }
        if (first.error == null) return@withContext first

        // API 不通（限流 / 被墙）时退一步，走网页重定向
        val second = runCatching { viaRedirect(currentVersion) }
            .getOrElse { UpdateOutcome(error = describe(it)) }
        if (second.error == null) return@withContext second

        // 两条都不通，把第一条的原因报出去（通常更有信息量）
        first
    }

    /** 走 API：能拿到更新说明和 APK 直链。 */
    private fun viaApi(currentVersion: String): UpdateOutcome {
        val request = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return UpdateOutcome(error = httpError(response.code))
            }
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return UpdateOutcome(error = "返回内容不是 JSON")
            val version = json.optString("tag_name").trim().trimStart('v', 'V')
            if (version.isBlank()) return UpdateOutcome(error = "返回里没有 tag_name")

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
            if (!isNewerVersion(version, currentVersion)) {
                return UpdateOutcome(latest = version)
            }
            return UpdateOutcome(
                info = UpdateInfo(
                    version = version,
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                ),
                latest = version,
            )
        }
    }

    /**
     * 走网页：`github.com/.../releases/latest` 会 302 到 `.../tag/vX.Y.Z`，
     * 从最终地址里就能读出最新版本号。不限额流。
     */
    private fun viaRedirect(currentVersion: String): UpdateOutcome {
        val request = Request.Builder().url(WEB_LATEST).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val version = versionFromReleaseUrl(finalUrl)
                ?: return UpdateOutcome(error = httpError(response.code))
            if (!isNewerVersion(version, currentVersion)) {
                return UpdateOutcome(latest = version)
            }
            val tag = "v" + version
            return UpdateOutcome(
                info = UpdateInfo(
                    version = version,
                    notes = "",
                    apkUrl = "https://github.com/" + OWNER + "/" + REPO +
                        "/releases/download/" + tag + "/DietCam-" + version + "-release.apk",
                    pageUrl = finalUrl,
                ),
                latest = version,
            )
        }
    }

    private fun httpError(code: Int): String = when (code) {
        403 -> "GitHub 接口限流了（HTTP 403），过一会儿再试"
        404 -> "仓库或 Release 不存在（HTTP 404）"
        else -> "GitHub 返回 HTTP " + code
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "网络不通：解析不了 github.com（可能需要代理）"
        is SocketTimeoutException -> "连接 GitHub 超时"
        else -> e.message ?: e.javaClass.simpleName
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
