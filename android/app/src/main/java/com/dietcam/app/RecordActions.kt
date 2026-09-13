package com.dietcam.app

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

/**
 * 点开一条记录后的详情弹窗。
 *
 * 列表里的卡片空间有限，描述只能显示两行；这里把这条记录的完整数据摊开 ——
 * 照片、热量、三大营养素、分项明细、建议、备注，底部再放原来的三个操作。
 * 内容整体可滚动，小屏手机也能看全。
 */
@Composable
fun RecordDetailDialog(
    record: MealRecord,
    vm: DietViewModel,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
) {
    var zoom by remember { mutableStateOf(false) }

    val bitmap = if (record.hasPhoto) {
        rememberArchivedPhoto(
            apiProvider = { DietApi(vm.settings()) },
            date = record.date,
            name = record.photo,
            maxDim = 1024,
        )
    } else {
        null
    }

    // 弹窗最高不超过屏幕高度的 84%，保证小屏上底部按钮始终可见
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.84f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 460.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(Radii.lg),
            color = Palette.Surface,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // 可滚动的主体：内容短时弹窗自动收缩，内容长时滚动查看
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (record.hasPhoto) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .background(Palette.SurfaceHigh)
                                .clickable(enabled = bitmap != null) { zoom = true },
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
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Palette.Accent,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                    DetailBody(record)
                }

                HairLine()

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DetailAction("✏️", "修改", false, Modifier.weight(1f)) { onEdit() }
                    DetailAction("🤖", "重新分析", false, Modifier.weight(1f)) { onReanalyze() }
                    DetailAction("🗑️", "删除", true, Modifier.weight(1f)) { onDelete() }
                }
            }
        }
    }

    if (zoom && bitmap != null) {
        PhotoViewerDialog(bitmap) { zoom = false }
    }
}

/** 弹窗里的数据部分。 */
@Composable
private fun DetailBody(record: MealRecord) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            record.title,
            color = Palette.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 25.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            record.date + " " + record.time,
            color = Palette.TextTertiary,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (record.meal.isNotBlank()) {
                Tag(record.meal, Palette.Accent)
                Spacer(Modifier.width(6.dp))
            }
            Tag(if (record.fromText) "文字记录" else "拍照记录", Palette.Info)
            if (!record.isFood) {
                Spacer(Modifier.width(6.dp))
                Tag("非食物", Palette.Warning)
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                record.nutrition.kcal.roundToInt().toString(),
                color = Palette.TextPrimary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "千卡",
                color = Palette.TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroStat("蛋白质", record.nutrition.protein, Palette.Protein, Modifier.weight(1f))
            MacroStat("碳水", record.nutrition.carbs, Palette.Carbs, Modifier.weight(1f))
            MacroStat("脂肪", record.nutrition.fat, Palette.Fat, Modifier.weight(1f))
        }

        if (record.items.isNotEmpty()) {
            SectionLabel("包含")
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                record.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(
                            item.name,
                            color = Palette.TextPrimary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.portion.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.portion,
                                color = Palette.TextTertiary,
                                fontSize = 11.sp,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            item.kcal.roundToInt().toString() + " 千卡",
                            color = Palette.TextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        if (record.advice.isNotBlank()) {
            SectionLabel("建议")
            Text(
                record.advice,
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }

        if (record.note.isNotBlank()) {
            SectionLabel("备注")
            Text(
                record.note,
                color = Palette.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, color = Palette.TextTertiary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun MacroStat(label: String, value: Double, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.roundToInt().toString(),
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                " g",
                color = Palette.TextTertiary,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = Palette.TextSecondary, fontSize = 10.5.sp)
    }
}

@Composable
private fun DetailAction(
    emoji: String,
    label: String,
    danger: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(42.dp),
        shape = RoundedCornerShape(Radii.sm),
        color = if (danger) Palette.Danger.copy(alpha = 0.14f) else Palette.SurfaceHigh,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 12.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                label,
                color = if (danger) Palette.Danger else Palette.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** 点照片后全屏查看原图。 */
@Composable
private fun PhotoViewerDialog(bitmap: Bitmap, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

/** 手动编辑一条记录。 */
@Composable
fun RecordEditDialog(
    record: MealRecord,
    running: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Map<String, Any>) -> Unit,
) {
    var title by remember { mutableStateOf(record.title) }
    var meal by remember { mutableStateOf(record.meal) }
    var kcal by remember { mutableStateOf(record.nutrition.kcal.roundToInt().toString()) }
    var protein by remember { mutableStateOf(record.nutrition.protein.roundToInt().toString()) }
    var carbs by remember { mutableStateOf(record.nutrition.carbs.roundToInt().toString()) }
    var fat by remember { mutableStateOf(record.nutrition.fat.roundToInt().toString()) }
    var advice by remember { mutableStateOf(record.advice) }
    var note by remember { mutableStateOf(record.note) }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("修改记录", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                EditField(title, { title = it }, "名称")
                Spacer(Modifier.height(8.dp))
                EditField(meal, { meal = it }, "餐次（早餐/午餐/晚餐/加餐）")
                Spacer(Modifier.height(8.dp))
                EditField(kcal, { kcal = it }, "热量（千卡）", number = true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditField(protein, { protein = it }, "蛋白质 g", number = true, modifier = Modifier.weight(1f))
                    EditField(carbs, { carbs = it }, "碳水 g", number = true, modifier = Modifier.weight(1f))
                    EditField(fat, { fat = it }, "脂肪 g", number = true, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                EditField(advice, { advice = it }, "建议")
                Spacer(Modifier.height(8.dp))
                EditField(note, { note = it }, "备注")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val fields = mutableMapOf<String, Any>(
                        "title" to title.trim(),
                        "meal" to meal.trim(),
                        "advice" to advice.trim(),
                        "note" to note.trim(),
                    )
                    kcal.trim().toDoubleOrNull()?.let { fields["calories_kcal"] = it }
                    protein.trim().toDoubleOrNull()?.let { fields["protein_g"] = it }
                    carbs.trim().toDoubleOrNull()?.let { fields["carbs_g"] = it }
                    fat.trim().toDoubleOrNull()?.let { fields["fat_g"] = it }
                    onSave(fields)
                },
                enabled = !running,
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text(
                    if (running) "保存中…" else "保存",
                    color = if (running) Palette.TextTertiary else Palette.OnAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (running) "先关掉" else "取消", color = Palette.TextSecondary)
            }
        },
    )
}

/** 让模型重新分析：先输入一句说明。 */
@Composable
fun ReanalyzeDialog(
    record: MealRecord,
    running: Boolean = false,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var instruction by remember {
        mutableStateOf(if (record.fromText) record.note else "")
    }
    AlertDialog(
        // 分析中不响应点外部，免得用户以为没反应而反复点
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("让模型重新分析", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    if (record.hasPhoto) {
                        "会用归档的原照片重新分析。可以补充一句说明让结果更准。"
                    } else {
                        "这条是文字记录，会用原文重新分析。"
                    },
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("例如：这是两人份 / 少油 / 米饭没吃完", fontSize = 13.sp, color = Palette.TextTertiary)
                    },
                    maxLines = 3,
                    shape = RoundedCornerShape(Radii.sm),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Accent,
                        unfocusedBorderColor = Palette.Outline,
                        cursorColor = Palette.Accent,
                    ),
                )
                if (running) {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Palette.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "正在重新分析…模型要十几秒到一分钟，结束后会告诉你改了什么",
                            color = Palette.TextPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(instruction.trim()) },
                enabled = !running,
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text(
                    if (running) "分析中…" else "重新分析",
                    color = if (running) Palette.TextTertiary else Palette.OnAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    if (running) "先关掉（后台继续）" else "取消",
                    color = Palette.TextSecondary,
                    fontSize = if (running) 12.sp else 14.sp,
                )
            }
        },
    )
}

/** 删除确认。 */
@Composable
fun DeleteConfirmDialog(
    record: MealRecord,
    running: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("删除这条记录？", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "「" + record.title + "」将被删除" +
                    (if (record.hasPhoto) "，归档的照片也会一并删掉。" else "。") +
                    "\n\n此操作不可撤销。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !running,
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger),
            ) {
                Text(
                    if (running) "删除中…" else "删除",
                    color = if (running) Palette.TextTertiary else Color.White,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (running) "先关掉" else "取消", color = Palette.TextSecondary)
            }
        },
    )
}

@Composable
private fun EditField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    number: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.sm),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            cursorColor = Palette.Accent,
        ),
    )
}
