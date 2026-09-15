package com.dietcam.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 打开系统相册挑一张图。
 *
 * 不用 ActivityResultContracts.GetContent()：那是 ACTION_GET_CONTENT，
 * 在华为 / EMUI 上经常被「文件管理」接走 —— 用户点的是相册，打开的是文件管理器。
 * ACTION_PICK + MediaStore.Images 才是「从相册选一张图」的标准意图，
 * 各家系统相册都注册了它；万一设备上真没有相册，再退回 ACTION_GET_CONTENT。
 *
 * 注意：Android 11 起有软件包可见性限制，resolveActivity() 会对别的应用返回 null，
 * 所以清单里必须声明 <queries>，否则这里会误判成「没有相册」而退回去。
 */
class PickGalleryImage : ActivityResultContract<Unit, Uri?>() {

    override fun createIntent(context: Context, input: Unit): Intent {
        val gallery = Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        }
        if (gallery.resolveActivity(context.packageManager) != null) {
            return gallery
        }
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

/**
 * 把刚拍的照片另存一份到系统相册。
 *
 * 相机拍下来的原图放在应用私有目录（cacheDir），系统相册扫不到它；
 * 用户想在手机相册里翻到自己拍的餐食，就得往 MediaStore 里插一份。
 */
object PhotoGallery {

    private const val TAG = "DietCam"
    private const val ALBUM = "DietCam"

    /** 保存成功返回 true；任何失败都吞掉，不能影响拍照主流程。 */
    fun saveToAlbum(context: Context, source: File): Boolean {
        if (!source.isFile || source.length() == 0L) return false
        val name = "DietCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveScoped(context, source, name)
            } else {
                saveLegacy(context, source, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "存入相册失败", e)
            false
        }
    }

    /** Android 10 起是分区存储：往 MediaStore 插一条即可，不需要任何权限。 */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, source: File, name: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
            // 写完之前先标记 pending，免得相册扫到半张图
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

        var written = false
        try {
            val output = resolver.openOutputStream(uri)
            if (output != null) {
                output.use { out -> source.inputStream().use { input -> input.copyTo(out) } }
                written = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "写入相册失败", e)
        }
        if (!written) {
            runCatching { resolver.delete(uri, null, null) }
            return false
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    /** Android 9 及以下：写公共 Pictures 目录，再让媒体扫描器把它收录进相册。 */
    private fun saveLegacy(context: Context, source: File, name: String): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false

        @Suppress("DEPRECATION")
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pictures, ALBUM)
        if (!dir.isDirectory && !dir.mkdirs()) return false

        val target = File(dir, name)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("image/jpeg"),
            null,
        )
        return true
    }
}
