package com.dietcam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** 归档照片的内存缓存，避免列表滚动时反复下载。 */
object PhotoCache {
    private const val MAX_ENTRIES = 40
    private val cache = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            if (size > MAX_ENTRIES) {
                return true
            }
            return false
        }
    }

    @Synchronized
    fun get(key: String): Bitmap? = cache[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        cache[key] = bitmap
    }

    fun key(date: String, name: String): String {
        val raw = date + "/" + name
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** 异步加载一张归档照片，返回可用的 Bitmap。 */
@Composable
fun rememberArchivedPhoto(
    /** 取归档照片的字节。远程模式下 DietApi 内部还有一层磁盘缓存，同一张只下一次。 */
    loader: suspend (String, String) -> ByteArray,
    date: String,
    name: String,
    maxDim: Int = 512,
): Bitmap? {
    val key = remember(date, name) { PhotoCache.key(date, name) }
    var bitmap by remember(key) { mutableStateOf(PhotoCache.get(key)) }

    LaunchedEffect(key) {
        if (bitmap != null || name.isBlank() || date.isBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = loader(date, name)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > maxDim) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }
        if (loaded != null) {
            PhotoCache.put(key, loaded)
            bitmap = loaded
        }
    }
    return bitmap
}

/** 从本地文件加载缩略图（用于定格预览）。 */
fun decodeLocal(file: File, maxDim: Int = 1600): Bitmap? {
    if (!file.exists() || file.length() == 0L) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxDim) sample *= 2
    return runCatching {
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}
