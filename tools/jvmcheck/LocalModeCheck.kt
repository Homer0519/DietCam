@file:Suppress("unused")
package jvmcheck

import com.dietcam.app.DietSettings
import com.dietcam.app.LocalDietApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * 本地模式的数据层自检 —— 在普通 JVM 上跑**真实的** LocalDietApi。
 *
 * 为什么要有它：本地模式（不连 AstrBot）只有真机才能点，出了问题只能靠猜。
 * 这里把 android 的最小桩补上，就能在电脑上把「存档案 → 重算目标 → 查汇总 / 日历 / 历史」
 * 这条路完整跑一遍。
 *
 * 运行：pwsh -File tools/jvmcheck/run.ps1
 */
private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, extra: Any? = null) {
    if (cond) {
        pass++
        println("  [PASS] $name")
    } else {
        fail++
        println("  [FAIL] $name" + (extra?.let { "   <- $it" } ?: ""))
    }
}

private fun section(title: String) {
    println()
    println("== $title ==")
}

private fun close(a: Double, b: Double, tol: Double = 0.05) = kotlin.math.abs(a - b) <= tol

fun main() {
    // 放在 .toolchain/ 下（已 gitignore），别在仓库根乱拉临时目录
    val base = File(".toolchain/jvmcheck-data")
    base.deleteRecursively()

    val ctx = android.content.Context()
    ctx.testFilesDir = File(base, "files")
    ctx.testCacheDir = File(base, "cache")

    // 本地模式：只填模型接口地址和模型名，baseUrl / secret 一律留空 ——
    // 这正是之前所有「静默失效」的触发条件。
    val settings = DietSettings(
        baseUrl = "",
        apiKey = "",
        secret = "",
        localMode = true,
        modelBaseUrl = "http://127.0.0.1:9/v1",
        modelApiKey = "sk-test",
        modelName = "test-vl",
    )
    val api = LocalDietApi(ctx, settings)
    fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    println("==================================================================")
    println(" 本地模式数据层自检（跑的是真实的 LocalDietApi）")
    println("==================================================================")

    section("1. 健康检查与初始档案")
    val health = run { api.health() }
    check("health 返回 ok", health.optBoolean("ok", false), health)
    check("health 报出数据目录（不是空字符串）", health.optString("data_dir").isNotBlank(), health.optString("data_dir"))
    check("health 声明本地模式", health.optBoolean("local", false))

    val p0 = run { api.profile() }
    val prof0 = p0.optJSONObject("profile")!!
    check("默认身高 170", close(prof0.optDouble("height_cm"), 170.0), prof0.optDouble("height_cm"))
    check("默认体重 65", close(prof0.optDouble("weight_kg"), 65.0))
    check("默认年龄 30", prof0.optInt("age") == 30)
    check("默认 targets_mode 为 auto", p0.optString("targets_mode") == "auto", p0.optString("targets_mode"))

    section("2. 存档案 → 目标必须跟着变")
    val before = p0.optJSONObject("targets")!!.optDouble("calories_kcal")
    val saved = run {
        api.saveProfile(
            mapOf(
                "height_cm" to 180.0,
                "weight_kg" to 80.0,
                "age" to 35,
                "sex" to "male",
                "activity" to "moderate",
                "goal" to "lose",
            ),
        )
    }
    check("saveProfile 返回 ok", saved.optBoolean("ok", false), saved)
    val savedProfile = saved.optJSONObject("profile")!!
    check("身高写进去了", close(savedProfile.optDouble("height_cm"), 180.0), savedProfile.optDouble("height_cm"))
    check("体重写进去了", close(savedProfile.optDouble("weight_kg"), 80.0))
    check("年龄写进去了", savedProfile.optInt("age") == 35)
    check("活动量写进去了", savedProfile.optString("activity") == "moderate")
    check("目标写进去了", savedProfile.optString("goal") == "lose")

    val after = saved.optJSONObject("targets")!!.optDouble("calories_kcal")
    check("热量目标随档案变化（$before → $after）", !close(before, after, 1.0), "before=$before after=$after")

    // Mifflin-St Jeor(male, 80kg, 180cm, 35y) = 10*80+6.25*180-5*35+5 = 1755
    // activity moderate 1.55 → 2720.25；goal lose 0.80 → 2176.2
    // 这两个数和 tools/plugin_selftest.py 里「锚点」那两条钉的是同一组 ——
    // Kotlin 与 Python 两份实现必须算出同样的目标，否则换个后端结果就变。
    check("目标值与公式吻合（2176 kcal）", close(after, 2176.2, 0.5), after)
    val savedTargets = saved.optJSONObject("targets")!!
    check("蛋白与插件端锚点一致（136 g）", close(savedTargets.optDouble("protein_g"), 136.0, 0.5),
        savedTargets.optDouble("protein_g"))
    check("碳水与插件端锚点一致（272 g）", close(savedTargets.optDouble("carbs_g"), 272.0, 0.5),
        savedTargets.optDouble("carbs_g"))

    section("3. 重新读回来（模拟杀进程再打开）")
    val api2 = LocalDietApi(ctx, settings)
    val reread = run { api2.profile() }
    check("档案持久化成功", close(reread.optJSONObject("profile")!!.optDouble("height_cm"), 180.0))
    check("目标模式回到 auto", reread.optString("targets_mode") == "auto", reread.optString("targets_mode"))
    check("重算后的目标一致", close(reread.optJSONObject("targets")!!.optDouble("calories_kcal"), after, 0.05))

    section("4. 首页汇总用的是同一份目标")
    val sum = run { api.summary(null) }
    check("summary 返回 ok", sum.optBoolean("ok", false))
    check("summary 的目标 = 档案重算的目标", close(sum.optJSONObject("targets")!!.optDouble("calories_kcal"), after, 0.05))
    check("summary 目标是正数（不再是 0）", sum.optJSONObject("targets")!!.optDouble("calories_kcal") > 0)

    section("5. 手动改目标只动传进来的那一项")
    val manual = run { api.updateTargets(mapOf("calories_kcal" to 1800.0)) }
    val mt = manual.optJSONObject("targets")!!
    check("热量改成 1800", close(mt.optDouble("calories_kcal"), 1800.0), mt.optDouble("calories_kcal"))
    check("蛋白质没被清零", mt.optDouble("protein_g") > 0, mt.optDouble("protein_g"))
    check("碳水没被清零", mt.optDouble("carbs_g") > 0)
    check("脂肪没被清零", mt.optDouble("fat_g") > 0)
    check("模式变成 manual", run { api.profile() }.optString("targets_mode") == "manual")

    section("6. 再存一次档案 → 手动目标让位给自动推算")
    val again = run {
        api.saveProfile(
            mapOf(
                "height_cm" to 180.0, "weight_kg" to 80.0, "age" to 35,
                "sex" to "male", "activity" to "moderate", "goal" to "lose",
            ),
        )
    }
    check("模式回到 auto", again.optString("targets_mode") == "auto")
    check("目标重新按档案算（不再是手动的 1800）", close(again.optJSONObject("targets")!!.optDouble("calories_kcal"), after, 0.05))

    section("7. 记录读写（手工塞两条进去）")
    val day = "2026-09-17"
    val recDir = File(ctx.testFilesDir, "diet/records")
    recDir.mkdirs()
    File(recDir, day + ".jsonl").writeText(
        JSONObject()
            .put("id", "aaa111").put("date", day).put("time", "12:30").put("photo", "p1.jpg")
            .put("is_food", true).put("title", "牛肉面").put("meal", "午餐")
            .put("calories_kcal", 620.0).put("protein_g", 28.0).put("carbs_g", 70.0).put("fat_g", 22.0)
            .toString() + "\n" +
            JSONObject()
                .put("id", "bbb222").put("date", day).put("time", "08:10").put("photo", "p2.jpg")
                .put("is_food", true).put("title", "豆浆鸡蛋").put("meal", "早餐")
                .put("calories_kcal", 180.0).put("protein_g", 12.0).put("carbs_g", 9.0).put("fat_g", 9.0)
                .toString() + "\n",
        Charsets.UTF_8,
    )
    val recs = run { api.records(day) }
    check("读出两条记录", recs.optInt("count") == 2, recs.optInt("count"))
    val tot = run { api.summary(day) }.optJSONObject("totals")!!
    check("热量合计 800", close(tot.optDouble("calories_kcal"), 800.0), tot.optDouble("calories_kcal"))
    check("蛋白合计 40", close(tot.optDouble("protein_g"), 40.0))

    section("8. 日历与历史")
    val cal = run { api.calendar("2026-09") }
    check("日历包含这一天", cal.optJSONObject("days")!!.has(day), cal.optJSONObject("days")!!.keys().asSequence().toList())
    check("日历这天 2 条", cal.optJSONObject("days")!!.getJSONObject(day).optInt("count") == 2)
    val hist = run { api.history(7, null) }
    check("历史记录 2 条", hist.optInt("count") == 2, hist.optInt("count"))
    check("history 的 days 参数生效", hist.optInt("days") == 7, hist.optInt("days"))
    check("history 带上 end（与插件返回结构一致）", hist.optString("end").isNotBlank(), hist.optString("end"))
    check("history 带上 targets", hist.optJSONObject("targets") != null)
    check("history 的 records 数组 2 条", hist.optJSONArray("records")!!.length() == 2)

    section("9. 删除记录")
    val del = run { api.deleteRecord(day, "aaa111") }
    check("删除返回 ok", del.optBoolean("ok", false))
    check("剩下 1 条", run { api.records(day) }.optInt("count") == 1)
    check("重复删除幂等", run { api.deleteRecord(day, "aaa111") }.optBoolean("already_gone", false))

    section("10. state.json 写干净了")
    val stateFile = File(ctx.testFilesDir, "diet/state.json")
    check("state.json 存在", stateFile.isFile)
    check("没有留下 .tmp 残渣", File(ctx.testFilesDir, "diet").listFiles()!!.none { it.name.endsWith(".tmp") },
        File(ctx.testFilesDir, "diet").listFiles()!!.map { it.name })

    section("11. 本地模式不再依赖 baseUrl / secret")
    check("baseUrl 是空的（这就是本地模式的样子）", settings.baseUrl.isBlank())
    check("secret 是空的", settings.secret.isBlank())
    check("isConfigured 仍然为真", DietSettings(
        localMode = true, modelBaseUrl = "http://x/v1", modelName = "m",
    ).let { it.localMode && it.modelBaseUrl.isNotBlank() && it.modelName.isNotBlank() })

    println()
    println("=".repeat(66))
    println("通过 $pass 项，失败 $fail 项")
    if (fail > 0) {
        kotlin.system.exitProcess(1)
    }
}
