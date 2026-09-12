package com.dietcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun CalendarScreen(vm: DietViewModel) {
    val month by vm.calendar.collectAsStateWithLifecycle()
    var current by remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(current) { vm.refreshCalendar(current.toString()) }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                MonthHeader(
                    month = current,
                    onPrev = { current = current.minusMonths(1) },
                    onNext = { current = current.plusMonths(1) },
                )
            }
            item { MonthGrid(current, month, onPick = { selectedDay = it }) }
            item { MonthSummary(month) }
        }
    }

    selectedDay?.let { day ->
        val records = month?.days?.get(day)
        DayDetailDialog(
            day = day,
            records = emptyList(),
            summary = records,
            onDismiss = { selectedDay = null },
        )
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上个月", tint = Palette.TextSecondary)
        }
        Text(
            month.year.toString() + " 年 " + month.monthValue + " 月",
            color = Palette.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下个月", tint = Palette.TextSecondary)
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    data: CalendarMonth?,
    onPick: (String) -> Unit,
) {
    val today = LocalDate.now()
    val first = month.atDay(1)
    // 周一为一周起点
    val leading = (first.dayOfWeek.value + 6) % 7
    val total = month.lengthOfMonth()
    val cells = leading + total
    val rows = (cells + 6) / 7

    Surface(color = Palette.Surface, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                    Text(
                        label,
                        color = Palette.TextTertiary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            for (row in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until 7) {
                        val index = row * 7 + col
                        val dayNumber = index - leading + 1
                        Box(Modifier.weight(1f).aspectRatio(0.86f)) {
                            if (dayNumber in 1..total) {
                                val date = month.atDay(dayNumber)
                                val key = date.toString()
                                val info = data?.days?.get(key)
                                DayCell(
                                    day = dayNumber,
                                    kcal = info?.nutrition?.kcal ?: 0.0,
                                    hasRecord = (info?.count ?: 0) > 0,
                                    isToday = date == today,
                                    target = data?.targets?.kcal ?: 0.0,
                                    onClick = { if ((info?.count ?: 0) > 0) onPick(key) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    kcal: Double,
    hasRecord: Boolean,
    isToday: Boolean,
    target: Double,
    onClick: () -> Unit,
) {
    val ratio = if (target > 0) (kcal / target).toFloat() else 0f
    val dotColor = when {
        !hasRecord -> Color.Transparent
        ratio > 1.05f -> Palette.Danger
        ratio >= 0.8f -> Palette.Accent
        else -> Palette.Carbs
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(RoundedCornerShape(Radii.sm))
            .background(if (isToday) Palette.SurfaceHigh else Color.Transparent)
            .clickable(enabled = hasRecord, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            day.toString(),
            color = if (isToday) Palette.Accent else Palette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(3.dp))
        if (hasRecord) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.height(2.dp))
            Text(
                kcal.roundToInt().toString(),
                color = Palette.TextTertiary,
                fontSize = 9.sp,
            )
        } else {
            Box(Modifier.size(6.dp))
            Spacer(Modifier.height(2.dp))
            Text(" ", fontSize = 9.sp)
        }
    }
}

@Composable
private fun MonthSummary(data: CalendarMonth?) {
    val days = data?.days?.values.orEmpty()
    if (days.isEmpty()) {
        Surface(color = Palette.Surface, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这个月还没有记录", color = Palette.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("有记录的日子会在这里显示一个小圆点", color = Palette.TextTertiary, fontSize = 12.sp)
            }
        }
        return
    }
    val totalKcal = days.sumOf { it.nutrition.kcal }
    val avg = totalKcal / days.size
    val target = data?.targets?.kcal ?: 0.0
    Surface(color = Palette.Surface, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("本月概况", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            StatLine("有记录的天数", days.size.toString() + " 天")
            StatLine("累计摄入", totalKcal.roundToInt().toString() + " 千卡")
            StatLine("日均摄入", avg.roundToInt().toString() + " 千卡")
            if (target > 0) {
                val diff = avg - target
                StatLine(
                    "与目标的差",
                    (if (diff >= 0) "+" else "") + diff.roundToInt() + " 千卡",
                    if (diff > 0) Palette.Danger else Palette.Accent,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(Palette.Accent, "达标")
                Spacer(Modifier.size(10.dp))
                Legend(Palette.Carbs, "偏少")
                Spacer(Modifier.size(10.dp))
                Legend(Palette.Danger, "超标")
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, valueColor: Color = Palette.TextPrimary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Palette.TextSecondary, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(4.dp))
        Text(text, color = Palette.TextTertiary, fontSize = 10.sp)
    }
}

/** 点某一天后弹出的当日明细（只显示合计，明细请回主页或回忆页）。 */
@Composable
private fun DayDetailDialog(
    day: String,
    records: List<MealRecord>,
    summary: DayTotals?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(day, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (summary == null) {
                    Text("这天没有记录", color = Palette.TextSecondary, fontSize = 13.sp)
                } else {
                    StatLine("餐数", summary.count.toString() + " 餐")
                    StatLine("热量", summary.nutrition.kcal.roundToInt().toString() + " 千卡")
                    StatLine("蛋白质", summary.nutrition.protein.roundToInt().toString() + " g")
                    StatLine("碳水", summary.nutrition.carbs.roundToInt().toString() + " g")
                    StatLine("脂肪", summary.nutrition.fat.roundToInt().toString() + " g")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了", color = Palette.Accent) }
        },
    )
}
