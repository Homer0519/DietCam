package com.dietcam.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本地模式的目标合并语义。
 *
 * 守的是一个真实踩过的坑：在手动目标界面里只填了热量就保存，
 * 结果蛋白/碳水/脂肪被整份覆盖写成 0，首页 MacroBar 直接显示 0 / 0g。
 * 插件版（main.py 的 api_targets）一直是「先读 current 再 merge」，
 * 只有本地版漏了这一步 —— 两端语义必须一致，否则同一个界面换个后端就出错。
 */
class TargetsMergeTest {

    private val auto = mapOf(
        "calories_kcal" to 2000.0,
        "protein_g" to 120.0,
        "carbs_g" to 220.0,
        "fat_g" to 55.0,
    )

    @Test
    fun 只改热量时其它三项保持不变() {
        val merged = mergeTargets(auto, mapOf("calories_kcal" to 1800.0))
        assertEquals(1800.0, merged["calories_kcal"]!!, 0.001)
        assertEquals(120.0, merged["protein_g"]!!, 0.001)
        assertEquals(220.0, merged["carbs_g"]!!, 0.001)
        assertEquals(55.0, merged["fat_g"]!!, 0.001)
    }

    @Test
    fun 四个键全传时全部生效() {
        val merged = mergeTargets(
            auto,
            mapOf(
                "calories_kcal" to 1600.0,
                "protein_g" to 130.0,
                "carbs_g" to 150.0,
                "fat_g" to 50.0,
            ),
        )
        assertEquals(1600.0, merged["calories_kcal"]!!, 0.001)
        assertEquals(130.0, merged["protein_g"]!!, 0.001)
        assertEquals(150.0, merged["carbs_g"]!!, 0.001)
        assertEquals(50.0, merged["fat_g"]!!, 0.001)
    }

    @Test
    fun 空更新不动任何东西() {
        val merged = mergeTargets(auto, emptyMap())
        assertEquals(auto.size, merged.size)
        auto.forEach { (key, value) -> assertEquals(value, merged[key]!!, 0.001) }
    }

    @Test
    fun 传进来的值保留一位小数() {
        val merged = mergeTargets(auto, mapOf("protein_g" to 123.456))
        assertEquals(123.5, merged["protein_g"]!!, 0.001)
    }

    @Test
    fun 底稿里没有的键会被补上() {
        val merged = mergeTargets(mapOf("calories_kcal" to 2000.0), mapOf("protein_g" to 90.0))
        assertEquals(2000.0, merged["calories_kcal"]!!, 0.001)
        assertEquals(90.0, merged["protein_g"]!!, 0.001)
    }
}
