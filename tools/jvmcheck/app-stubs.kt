@file:Suppress("unused")
package com.dietcam.app

import java.security.MessageDigest

/**
 * 自检用替身：真实的 PhotoCache 依赖 Compose，只有 key() 是 DietApi 用到的。
 * 这条路径（远程模式的磁盘图缓存）不在本地模式自检的覆盖范围内。
 */
object PhotoCache {
    fun key(date: String, name: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest((date + "/" + name).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
