@file:Suppress("PackageDirectoryMismatch", "unused")
package android.util

/** 仅供 JVM 自检使用：用 JDK 的 Base64 顶替 android.util.Base64。 */
object Base64 {
    const val NO_WRAP = 2
    fun encodeToString(input: ByteArray, flags: Int): String =
        java.util.Base64.getEncoder().encodeToString(input)
}
