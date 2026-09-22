package com.dietcam.app

import org.json.JSONObject
import kotlin.math.max

/** 一份营养数据（摄入量或目标值）。 */
data class Nutrition(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    /** 运动消耗（只有「当天合计」这一处有值）。 */
    val burned: Double = 0.0,
) {
    /** 净摄入 = 吃进去的 − 运动消耗的。 */
    val net: Double get() = kcal - burned

    companion object {
        fun from(obj: JSONObject?): Nutrition {
            if (obj == null) return Nutrition()
            return Nutrition(
                kcal = obj.optDouble("calories_kcal", 0.0),
                protein = obj.optDouble("protein_g", 0.0),
                carbs = obj.optDouble("carbs_g", 0.0),
                fat = obj.optDouble("fat_g", 0.0),
                burned = obj.optDouble("burned_kcal", 0.0),
            )
        }
    }
}

/** 放纵日状态。下次日期与倒计时都是后端算好的，前端只负责显示。 */
data class CheatStatus(
    val enabled: Boolean = false,
    val intervalDays: Int = 7,
    val last: String = "",
    val next: String = "",
    val daysUntil: Int = 0,
    val isToday: Boolean = false,
)

/** 一条饮食记录。 */
data class MealRecord(
    val id: String,
    val date: String,
    val time: String,
    val meal: String,
    val title: String,
    val nutrition: Nutrition,
    val photo: String,
    val advice: String,
    val note: String,
    val isFood: Boolean,
    val fromText: Boolean,
    val items: List<MealItem> = emptyList(),
) {
    val hasPhoto: Boolean get() = photo.isNotBlank()
}

data class MealItem(
    val name: String,
    val portion: String,
    val kcal: Double,
)

/** 主页需要的当日概览。 */
data class DaySummary(
    val date: String,
    val totals: Nutrition,
    val targets: Nutrition,
    val records: List<MealRecord>,
) {
    val count: Int get() = records.size

    /** 热量完成度，0..1；目标为 0 时返回 0。 */
    val calorieProgress: Float
        get() = if (targets.kcal <= 0) 0f else (totals.kcal / targets.kcal).toFloat().coerceIn(0f, 1f)

    val remainingKcal: Double get() = max(0.0, targets.kcal - totals.kcal)

    /** 超出目标时返回超出量，否则 0。 */
    val overKcal: Double get() = max(0.0, totals.kcal - targets.kcal)
}

object JsonParse {

    fun cheat(obj: JSONObject?): CheatStatus {
        val src = obj?.optJSONObject("cheat") ?: obj ?: return CheatStatus()
        return CheatStatus(
            enabled = src.optBoolean("enabled", false),
            intervalDays = src.optInt("interval_days", 7),
            last = src.optString("last"),
            next = src.optString("next"),
            daysUntil = src.optInt("days_until", 0),
            isToday = src.optBoolean("is_today", false),
        )
    }


    fun record(obj: JSONObject): MealRecord {
        val items = mutableListOf<MealItem>()
        obj.optJSONArray("items")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                items.add(
                    MealItem(
                        name = o.optString("name", "?"),
                        portion = o.optString("portion", ""),
                        kcal = o.optDouble("calories_kcal", 0.0),
                    )
                )
            }
        }
        return MealRecord(
            id = obj.optString("id", ""),
            date = obj.optString("date", ""),
            time = obj.optString("time", ""),
            meal = obj.optString("meal", ""),
            title = obj.optString("title", "一餐"),
            nutrition = Nutrition.from(obj),
            photo = obj.optString("photo", ""),
            advice = obj.optString("advice", ""),
            note = obj.optString("note", ""),
            isFood = obj.optBoolean("is_food", true),
            fromText = obj.optString("source", "") == "app-text",
            items = items,
        )
    }

    fun summary(obj: JSONObject): DaySummary {
        val records = mutableListOf<MealRecord>()
        obj.optJSONArray("records")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { records.add(record(it)) }
            }
        }
        return DaySummary(
            date = obj.optString("date", ""),
            totals = Nutrition.from(obj.optJSONObject("totals")),
            targets = Nutrition.from(obj.optJSONObject("targets")),
            records = records,
        )
    }
}

/** 身体档案。 */
data class BodyProfile(
    val heightCm: Double = 170.0,
    val weightKg: Double = 65.0,
    val age: Int = 30,
    val sex: String = "male",
    val activity: String = "light",
    val goal: String = "maintain",
) {
    val sexLabel: String get() = if (sex == "female") "女" else "男"
}

/** /profile 的完整返回。 */
data class ProfileInfo(
    val profile: BodyProfile,
    val suggested: Nutrition,
    val targets: Nutrition,
    val mode: String,
)

/** 日历里某一天的合计。 */
data class DayTotals(
    val date: String,
    val nutrition: Nutrition,
    val count: Int,
)

data class CalendarMonth(
    val month: String,
    val targets: Nutrition,
    val days: Map<String, DayTotals>,
)

object ProfileParse {
    fun profile(obj: JSONObject?): BodyProfile {
        if (obj == null) return BodyProfile()
        return BodyProfile(
            heightCm = obj.optDouble("height_cm", 170.0),
            weightKg = obj.optDouble("weight_kg", 65.0),
            age = obj.optInt("age", 30),
            sex = obj.optString("sex", "male"),
            activity = obj.optString("activity", "light"),
            goal = obj.optString("goal", "maintain"),
        )
    }

    fun info(obj: JSONObject) = ProfileInfo(
        profile = profile(obj.optJSONObject("profile")),
        suggested = Nutrition.from(obj.optJSONObject("suggested")),
        targets = Nutrition.from(obj.optJSONObject("targets")),
        mode = obj.optString("targets_mode", "config"),
    )

    fun calendar(obj: JSONObject): CalendarMonth {
        val days = mutableMapOf<String, DayTotals>()
        obj.optJSONObject("days")?.let { root ->
            for (key in root.keys()) {
                val item = root.optJSONObject(key) ?: continue
                days[key] = DayTotals(
                    date = key,
                    nutrition = Nutrition.from(item),
                    count = item.optInt("count", 0),
                )
            }
        }
        return CalendarMonth(
            month = obj.optString("month", ""),
            targets = Nutrition.from(obj.optJSONObject("targets")),
            days = days,
        )
    }
}

object ActivityLabels {
    val activity = linkedMapOf(
        "sedentary" to "久坐（几乎不运动）",
        "light" to "轻度（每周 1-3 次）",
        "moderate" to "中度（每周 3-5 次）",
        "active" to "高度（每周 6-7 次）",
        "very_active" to "极高（体力工作/一天两练）",
    )
    val goal = linkedMapOf(
        "lose" to "减脂",
        "maintain" to "维持",
        "gain" to "增重",
    )
    fun activityLabel(key: String) = activity[key] ?: key
    fun goalLabel(key: String) = goal[key] ?: key
}
