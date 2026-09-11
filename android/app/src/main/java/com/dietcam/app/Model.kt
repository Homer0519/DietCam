package com.dietcam.app

import org.json.JSONObject
import kotlin.math.max

/** 一份营养数据（摄入量或目标值）。 */
data class Nutrition(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
) {
    companion object {
        fun from(obj: JSONObject?): Nutrition {
            if (obj == null) return Nutrition()
            return Nutrition(
                kcal = obj.optDouble("calories_kcal", 0.0),
                protein = obj.optDouble("protein_g", 0.0),
                carbs = obj.optDouble("carbs_g", 0.0),
                fat = obj.optDouble("fat_g", 0.0),
            )
        }
    }
}

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
