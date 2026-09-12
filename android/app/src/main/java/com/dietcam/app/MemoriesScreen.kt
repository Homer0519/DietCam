package com.dietcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/** 回忆页：把最近这些天的记录按日期串成时间线。 */
@Composable
fun MemoriesScreen(vm: DietViewModel) {
    val history by vm.history.collectAsStateWithLifecycle()
    var days by remember { mutableStateOf(30) }
    var actionTarget by remember { mutableStateOf<MealRecord?>(null) }
    var editing by remember { mutableStateOf<MealRecord?>(null) }
    var reanalyzing by remember { mutableStateOf<MealRecord?>(null) }
    var deleting by remember { mutableStateOf<MealRecord?>(null) }

    LaunchedEffect(days) { vm.refreshHistory(days) }

    val grouped = remember(history) {
        history.groupBy { it.date }.toSortedMap(compareByDescending { it })
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("回忆", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Row {
                        listOf(7 to "7 天", 30 to "30 天", 90 to "90 天").forEach { (value, label) ->
                            TextButton(onClick = { days = value }) {
                                Text(
                                    label,
                                    color = if (days == value) Palette.Accent else Palette.TextTertiary,
                                    fontSize = 12.sp,
                                    fontWeight = if (days == value) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }

            if (history.isEmpty()) {
                item { EmptyMemories() }
            } else {
                grouped.forEach { (date, records) ->
                    item(key = "h-" + date) { DayHeader(date, records) }
                    items(records, key = { it.id.ifBlank { it.date + it.time + it.title } }) { record ->
                        MemoryRow(record, vm, onClick = { actionTarget = record })
                    }
                }
            }
        }
    }

    actionTarget?.let { record ->
        RecordDetailDialog(
            record = record,
            vm = vm,
            onDismiss = { actionTarget = null },
            onEdit = { editing = record; actionTarget = null },
            onReanalyze = { reanalyzing = record; actionTarget = null },
            onDelete = { deleting = record; actionTarget = null },
        )
    }

    editing?.let { record ->
        RecordEditDialog(
            record = record,
            onDismiss = { editing = null },
            onSave = { fields ->
                vm.updateRecord(record.date, record, fields) { editing = null }
            },
        )
    }

    reanalyzing?.let { record ->
        ReanalyzeDialog(
            record = record,
            onDismiss = { reanalyzing = null },
            onRun = { instruction ->
                vm.reanalyzeRecord(record.date, record, instruction) { reanalyzing = null }
            },
        )
    }

    deleting?.let { record ->
        DeleteConfirmDialog(
            record = record,
            onDismiss = { deleting = null },
            onConfirm = { vm.deleteRecord(record.date, record) { deleting = null } },
        )
    }
}

@Composable
private fun DayHeader(date: String, records: List<MealRecord>) {
    val kcal = records.filter { it.isFood }.sumOf { it.nutrition.kcal }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(date, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            records.size.toString() + " 餐 · " + kcal.roundToInt() + " 千卡",
            color = Palette.TextTertiary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun MemoryRow(record: MealRecord, vm: DietViewModel, onClick: () -> Unit) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(Radii.md),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val bitmap = if (record.hasPhoto) {
                rememberArchivedPhoto(
                    apiProvider = { DietApi(vm.settings()) },
                    date = record.date,
                    name = record.photo,
                )
            } else {
                null
            }
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(Palette.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(if (record.hasPhoto) "📷" else "✍️", fontSize = 17.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(record.time, color = Palette.TextTertiary, fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(record.meal, color = Palette.Accent, fontSize = 11.sp)
                    if (record.note.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text("· " + record.note.take(14), color = Palette.TextTertiary, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(record.title, color = Palette.TextPrimary, fontSize = 14.sp, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (record.isFood) record.nutrition.kcal.roundToInt().toString() else "—",
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyMemories() {
    Surface(color = Palette.Surface, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🕰️", fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text("这段时间还没有记录", color = Palette.TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text("记录几餐之后，这里会变成你的饮食时间线", color = Palette.TextTertiary, fontSize = 12.sp)
        }
    }
}
