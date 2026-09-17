package com.dietcam.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    vm: DietViewModel,
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by vm.home.collectAsStateWithLifecycle()
    var actionTarget by remember { mutableStateOf<MealRecord?>(null) }
    var editing by remember { mutableStateOf<MealRecord?>(null) }
    var reanalyzing by remember { mutableStateOf<MealRecord?>(null) }
    var deleting by remember { mutableStateOf<MealRecord?>(null) }
    val busy by vm.busy.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HomeHeader(state, onRefresh = { vm.refreshHome() }, onSettings = onOpenSettings) }

            when {
                state.loading -> item { LoadingBlock() }

                state.error != null -> item { ErrorBlock(state.error!!) { vm.refreshHome() } }

                state.summary != null -> {
                    val summary = state.summary!!
                    item { TodayCard(summary, vm) }
                    item { SectionTitle("今日记录", summary.count) }
                    if (summary.records.isEmpty()) {
                        item { EmptyRecords(onOpenCamera) }
                    } else {
                        items(summary.records, key = { it.id.ifBlank { it.time + it.title } }) { record ->
                            RecordRow(record, vm, summary.date) { actionTarget = record }
                        }
                    }
                }
            }
        }

        CameraFab(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            onClick = onOpenCamera,
        )
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
private fun HomeHeader(state: HomeUiState, onRefresh: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("今天", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                state.summary?.date?.takeIf { it.isNotBlank() } ?: "—",
                color = Palette.TextTertiary,
                fontSize = 12.sp,
            )
        }
        if (state.refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = Palette.Accent,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = Palette.TextSecondary)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Palette.TextSecondary)
        }
    }
}

@Composable
private fun TodayCard(summary: DaySummary, vm: DietViewModel) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(Radii.lg),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(
                    progress = summary.calorieProgress,
                    over = summary.overKcal > 0,
                    modifier = Modifier.size(132.dp),
                    strokeWidth = 13.dp,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            summary.totals.kcal.roundToInt().toString(),
                            color = Palette.TextPrimary,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("千卡", color = Palette.TextTertiary, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.width(20.dp))

                Column(Modifier.weight(1f)) {
                    if (summary.targets.kcal > 0) {
                        if (summary.overKcal > 0) {
                            Text("已超出", color = Palette.TextSecondary, fontSize = 12.sp)
                            Text(
                                "＋" + summary.overKcal.roundToInt() + " 千卡",
                                color = Palette.Danger,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text("今日还可摄入", color = Palette.TextSecondary, fontSize = 12.sp)
                            Text(
                                summary.remainingKcal.roundToInt().toString(),
                                color = Palette.Accent,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text("千卡", color = Palette.TextTertiary, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "目标 " + summary.targets.kcal.roundToInt() + " 千卡",
                            color = Palette.TextTertiary,
                            fontSize = 11.sp,
                        )
                    } else {
                        Text("未设置目标", color = Palette.TextSecondary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Tag(
                        text = "共 " + summary.count + " 餐",
                        color = Palette.Info,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HairLine()
            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                MacroBar("蛋白质", summary.totals.protein, summary.targets.protein, Palette.Protein)
                MacroBar("碳水", summary.totals.carbs, summary.targets.carbs, Palette.Carbs)
                MacroBar("脂肪", summary.totals.fat, summary.targets.fat, Palette.Fat)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        if (count > 0) {
            Text(count.toString(), color = Palette.TextTertiary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RecordRow(
    record: MealRecord,
    vm: DietViewModel,
    date: String,
    onClick: () -> Unit,
) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(Radii.md),
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (record.hasPhoto) {
                val bitmap = rememberArchivedPhoto(
                    loader = { d, n -> vm.photoBytes(d, n) },
                    date = date,
                    name = record.photo,
                )
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(Palette.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("📷", fontSize = 18.sp)
                    }
                }
            } else {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(Palette.SurfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✍️", fontSize = 18.sp)
                }
            }

            Spacer(Modifier.width(12.dp))

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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                )
                if (record.advice.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        record.advice,
                        color = Palette.TextTertiary,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (record.isFood) {
                    Text(
                        record.nutrition.kcal.roundToInt().toString(),
                        color = Palette.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("千卡", color = Palette.TextTertiary, fontSize = 10.sp)
                } else {
                    Text("非食物", color = Palette.TextTertiary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(Radii.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("连不上服务", color = Palette.Danger, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(message, color = Palette.TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(onClick = onRetry) {
                Text("重试", color = Palette.Accent)
            }
        }
    }
}

@Composable
private fun EmptyRecords(onOpenCamera: () -> Unit) {
    Surface(
        color = Palette.Surface,
        shape = RoundedCornerShape(Radii.md),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🍽️", fontSize = 30.sp)
            Spacer(Modifier.height(10.dp))
            Text("今天还没有记录", color = Palette.TextPrimary, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "拍一张照片，或直接打字描述吃了什么",
                color = Palette.TextTertiary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            androidx.compose.material3.TextButton(onClick = onOpenCamera) {
                Text("现在记录", color = Palette.Accent)
            }
        }
    }
}

/** 主页底部的大圆钮：打开相机。 */
@Composable
private fun CameraFab(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.size(72.dp),
        shape = CircleShape,
        color = Palette.Accent,
        shadowElevation = 12.dp,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "打开相机",
                tint = Palette.OnAccent,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}