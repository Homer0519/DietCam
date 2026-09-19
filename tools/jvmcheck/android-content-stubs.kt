@file:Suppress("PackageDirectoryMismatch", "unused")
package android.content

import java.io.File

/**
 * 仅供 JVM 自检使用的 android.content 桩。
 *
 * 目的是让**真实的** LocalDietApi.kt / SettingsStore.kt 能在普通 JVM 上编译并运行，
 * 从而不必依赖模拟器或真机就能验证本地模式的数据逻辑。
 * 只实现这两处真正用到的成员，多一个都不要加 —— 桩写得越"好用"，
 * 越容易掩盖真实设备上的差异。
 */
open class Context {
    var testFilesDir: File = File(".toolchain/jvmcheck-data/files")
    var testCacheDir: File = File(".toolchain/jvmcheck-data/cache")
    val filesDir: File get() = testFilesDir
    val cacheDir: File get() = testCacheDir
    private val prefs = MemoryPrefs()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs

    companion object {
        const val MODE_PRIVATE = 0
    }
}

interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun apply()
    }
}

class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            map[key] = value
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            map[key] = value
            return this
        }
        override fun apply() = Unit
    }
}
