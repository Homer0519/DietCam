package com.dietcam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 点开一条记录后的操作面板：编辑 / AI 重分析 / 删除。 */
@Composable
fun RecordActionSheet(
    record: MealRecord,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onReanalyze: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(record.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    record.date + " " + record.time + " · " + record.meal,
                    color = Palette.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        },
        text = {
            Column {
                ActionRow("✏️", "手动修改", "改名字、热量、三大营养素和建议") { onEdit() }
                ActionRow("🤖", "让模型重新分析", "补充一句说明，例如「这是两人份」") { onReanalyze() }
                ActionRow("🗑️", "删除这条记录", "会连同归档照片一起删除", danger = true) { onDelete() }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextSecondary) }
        },
    )
}

@Composable
private fun ActionRow(
    emoji: String,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.sm),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(emoji, fontSize = 17.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = if (danger) Palette.Danger else Palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(subtitle, color = Palette.TextTertiary, fontSize = 11.sp)
            }
        }
    }
}

/** 手动编辑一条记录。 */
@Composable
fun RecordEditDialog(
    record: MealRecord,
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
        onDismissRequest = onDismiss,
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
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text("保存", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextSecondary) }
        },
    )
}

/** 让模型重新分析：先输入一句说明。 */
@Composable
fun ReanalyzeDialog(
    record: MealRecord,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    var instruction by remember {
        mutableStateOf(if (record.fromText) record.note else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(instruction.trim()) },
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text("重新分析", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextSecondary) }
        },
    )
}

/** 删除确认。 */
@Composable
fun DeleteConfirmDialog(
    record: MealRecord,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Danger),
            ) {
                Text("删除", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextSecondary) }
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
