package com.dietcam.app

/**
 * 去掉字符串里**所有**空白字符（空格、换行、制表符…）。
 *
 * 用户粘贴 API Key / 地址时，很容易把换行一起带进来。OkHttp 对请求头很严格，
 * 会直接抛：
 *
 *     Unexpected char 0x0a at 100 in Authorization value
 *
 * 在手机上表现为「测试连接 / 拉取模型列表点了没反应」。密钥与 URL 里本来
 * 就不允许出现空白，所以一律删掉最省事 —— 只 trim 首尾是不够的，
 * 换行可能夹在中间。
 *
 * 存进去的时候清一次（SettingsStore），用的时候再清一次（两个后端），
 * 这样以前存坏的设置也能自愈。
 */
internal fun stripWhitespace(value: String): String = value.filterNot { it.isWhitespace() }

/** 接口地址：去空白。 */
internal fun sanitizeUrl(value: String): String = stripWhitespace(value)

/** 密钥（模型 key、AstrBot API Key、签名密钥）：去空白。 */
internal fun sanitizeKey(value: String): String = stripWhitespace(value)

/** 模型名：只去首尾空白，名字中间的空格保留。 */
internal fun sanitizeName(value: String): String = value.trim()
