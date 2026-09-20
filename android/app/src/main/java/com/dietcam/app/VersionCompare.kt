package com.dietcam.app

/**
 * 逐段比较版本号：1.10.0 > 1.9.9（按字符串比就错了）。
 * 段数不齐按 0 补；非数字后缀（1.2.3-beta）只取前面的数字。
 *
 * 单独放一个文件、不引任何 Android 类型，是为了能在 JVM 自检里直接验。
 */
internal fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = versionParts(candidate)
    val b = versionParts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/**
 * 从 `https://github.com/o/r/releases/tag/v2.7.3` 里取出 `2.7.3`。
 * 拿不到（比如根本没发生重定向）就返回 null。
 *
 * API 被限流时更新检查会退到网页重定向那条路，版本号就是从这里读出来的。
 */
internal fun versionFromReleaseUrl(url: String): String? {
    val tag = url.substringAfterLast("/tag/", "")
    if (tag.isBlank() || tag == url) return null
    val version = tag.trim().trimStart('v', 'V').substringBefore('?')
    return version.takeIf { it.isNotBlank() }
}

private fun versionParts(text: String): List<Int> =
    text.trim().removePrefix("v").removePrefix("V")
        // 预发布/构建元数据不参与比较：2.7.0-beta.1 就等于 2.7.0，
        // 不该被判成"比正式版新"。
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
