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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.CircularProgressIndicator
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
    var pickTarget by remember { mutableStateOf<MealRecord?>(null) }
    var editing by remember { mutableStateOf<MealRecord?>(null) }
    var reanalyzing by remember { mutableStateOf<MealRecord?>(null) }
    var deleting by remember { mutableStateOf<MealRecord?>(null) }
    val dayDetail by vm.dayRecords.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LaunchedEffect(current) { vm.refreshCalendar(current.toString()) }
    LaunchedEffect(selectedDay) { selectedDay?.let { vm.loadDay(it) } }

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

    val detail = dayDetail
    // 打开某一条的详情时先把当天列表收起来，避免两层弹窗叠在一起
    if (detail != null && pickTarget == null) {
        DayDetailDialog(
            detail = detail,
            onPickRecord = { pickTarget = it },
            onDismiss = {
                selectedDay = null
                vm.closeDay()
            },
        )
    }

    pickTarget?.let { record ->
        RecordDetailDialog(
            record = record,
            vm = vm,
            onDismiss = { pickTarget = null },
            onEdit = { editing = record; pickTarget = null },
            onReanalyze = { reanalyzing = record; pickTarget = null },
            onDelete = { deleting = record; pickTarget = null },
        )
    }

    editing?.let { record ->
        RecordEditDialog(
            record = record,
            running = busy,
            onDismiss = { editing = null },
            onSave = { fields -> vm.updateRecord(record.date, record, fields) { editing = null } },
        )
    }

    reanalyzing?.let { record ->
        ReanalyzeDialog(
            record = record,
            running = busy,
            onDismiss = { reanalyzing = null },
            onRun = { instruction ->
                vm.reanalyzeRecord(record.date, record, instruction) { reanalyzing = null }
            },
        )
    }

    deleting?.let { record ->
        DeleteConfirmDialog(
            record = record,
            running = busy,
            onDismiss = { deleting = null },
            onConfirm = { vm.deleteRecord(record.date, record) { deleting = null } },
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

/** 点某一天后弹出的当日明细：这天吃了什么，点一条还能继续改。 */
@Composable
private fun DayDetailDialog(
    detail: DayRecords,
    onPickRecord: (MealRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val foods = detail.records.filter { it.isFood }
    val kcal = foods.sumOf { it.nutrition.kcal }
    val protein = foods.sumOf { it.nutrition.protein }
    val carbs = foods.sumOf { it.nutrition.carbs }
    val fat = foods.sumOf { it.nutrition.fat }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    detail.date + "　" + weekdayLabel(detail.date),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
                if (!detail.loading && detail.error == null && detail.records.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        detail.records.size.toString() + " 餐 · " + kcal.roundToInt() + " 千卡",
                        color = Palette.TextTertiary,
                        fontSize = 11.sp,
                    )
                }
            }
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    detail.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Palette.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("正在读取…", color = Palette.TextSecondary, fontSize = 13.sp)
                    }

                    detail.error != null -> Text(
                        "读取失败：" + detail.error,
                        color = Palette.Danger,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )

                    detail.records.isEmpty() -> Text(
                        "这天没有记录。",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                    )

                    else -> {
                        detail.records.forEach { record ->
                            DayRecordRow(record) { onPickRecord(record) }
                            Spacer(Modifier.height(8.dp))
                        }
                        Spacer(Modifier.height(4.dp))
                        HairLine()
                        Spacer(Modifier.height(10.dp))
                        StatLine("合计", kcal.roundToInt().toString() + " 千卡")
                        StatLine("蛋白质", protein.roundToInt().toString() + " g")
                        StatLine("碳水", carbs.roundToInt().toString() + " g")
                        StatLine("脂肪", fat.roundToInt().toString() + " g")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点任意一条可以看详情、改内容或让模型重新分析。",
                            color = Palette.TextTertiary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Palette.Accent) }
        },
    )
}

@Composable
private fun DayRecordRow(record: MealRecord, onClick: () -> Unit) {
    Surface(
        color = Palette.SurfaceHigh,
        shape = RoundedCornerShape(Radii.sm),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (record.isFood) (if (record.fromText) "✍️" else "📷") else "❔",
                fontSize = 16.sp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.time, color = Palette.TextTertiary, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(record.meal, color = Palette.Accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    record.title,
                    color = Palette.TextPrimary,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (record.isFood) record.nutrition.kcal.roundToInt().toString() else "—",
                color = Palette.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 2026-09-15 -> 周二 */
private fun weekdayLabel(date: String): String = runCatching {
    when (LocalDate.parse(date).dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
}.getOrDefault("")