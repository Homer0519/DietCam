@file:Suppress("PackageDirectoryMismatch", "unused")
package android.graphics

import java.io.OutputStream

/** 仅供 JVM 自检使用的最小图形桩：只为让 LocalDietApi 的取图编码路径通过编译。 */
class Bitmap {
    enum class CompressFormat { JPEG, PNG, WEBP }

    fun compress(format: CompressFormat, quality: Int, out: OutputStream): Boolean {
        out.write(ByteArray(0))
        return true
    }
}

object BitmapFactory {
    class Options {
        var inJustDecodeBounds = false
        var inSampleSize = 1
        var outWidth = 0
        var outHeight = 0
    }

    fun decodeFile(path: String, opts: Options? = null): Bitmap? {
        opts?.let { it.outWidth = 1; it.outHeight = 1 }
        return Bitmap()
    }
}
