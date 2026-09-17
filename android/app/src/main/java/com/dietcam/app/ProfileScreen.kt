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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun ProfileScreen(
    vm: DietViewModel,
    onOpenSettings: () -> Unit,
) {
    val info by vm.profile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val serverVersion by vm.serverVersion.collectAsStateWithLifecycle()

    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("male") }
    var activity by remember { mutableStateOf("light") }
    var goal by remember { mutableStateOf("maintain") }

    // 只填充一次，之后交给用户编辑
    var filled by remember { mutableStateOf(false) }
    LaunchedEffect(info) {
        val p = info?.profile ?: return@LaunchedEffect
        if (filled) return@LaunchedEffect
        height = p.heightCm.roundToInt().toString()
        weight = p.weightKg.roundToInt().toString()
        age = p.age.toString()
        sex = p.sex
        activity = p.activity
        goal = p.goal
        filled = true
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("我的", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onOpenSettings) {
                        Text("连接设置", color = Palette.Accent, fontSize = 13.sp)
                    }
                }
            }

            item {
                SectionCard("身体档案") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(height, { height = it }, "身高 cm", Modifier.weight(1f))
                        NumberField(weight, { weight = it }, "体重 kg", Modifier.weight(1f))
                        NumberField(age, { age = it }, "年龄", Modifier.weight(0.8f))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("性别", color = Palette.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip("男", sex == "male", Modifier.weight(1f)) { sex = "male" }
                        ChoiceChip("女", sex == "female", Modifier.weight(1f)) { sex = "female" }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("活动量", color = Palette.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ActivityLabels.activity.forEach { (key, label) ->
                            ChoiceRow(label, activity == key) { activity = key }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("目标", color = Palette.TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActivityLabels.goal.forEach { (key, label) ->
                            ChoiceChip(label, goal == key, Modifier.weight(1f)) { goal = key }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            vm.saveProfile(
                                BodyProfile(
                                    heightCm = height.toDoubleOrNull() ?: 170.0,
                                    weightKg = weight.toDoubleOrNull() ?: 65.0,
                                    age = age.toIntOrNull() ?: 30,
                                    sex = sex,
                                    activity = activity,
                                    goal = goal,
                                )
                            )
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(Radii.sm),
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
                    ) {
                        Text(
                            if (busy) "保存中…" else "保存并重算目标",
                            color = Palette.OnAccent,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            item { TargetsCard(info, vm) }

            item {
                SectionCard("关于") {
                    // 版本号统一从构建产物读，别再手写 —— 之前这里写死 2.1.0，
                    // 而实际构建早就是 2.6.x 了，用户报障会被这个数字带偏。
                    AboutLine("当前版本", BuildConfig.VERSION_NAME)
                    if (vm.settings().localMode) {
                        AboutLine("运行方式", "本地模式 · 不依赖 AstrBot")
                    } else {
                        AboutLine("服务端插件", serverVersion?.let { "v" + it } ?: "未连接")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "目标按 Mifflin-St Jeor 公式推算：先算基础代谢，再乘活动系数，最后按减脂/维持/增重调整。",
                        color = Palette.TextTertiary,
                        fontSize = 11.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetsCard(info: ProfileInfo?, vm: DietViewModel) {
    var manual by remember { mutableStateOf(false) }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    LaunchedEffect(info) {
        val t = info?.targets ?: return@LaunchedEffect
        kcal = t.kcal.roundToInt().toString()
        protein = t.protein.roundToInt().toString()
        carbs = t.carbs.roundToInt().toString()
        fat = t.fat.roundToInt().toString()
        manual = info.mode == "manual"
    }

    SectionCard("每日目标") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (info?.mode == "manual") "手动设定" else "按档案自动推算",
                color = if (info?.mode == "manual") Palette.Carbs else Palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { manual = !manual }) {
                Text(if (manual) "收起" else "手动修改", color = Palette.TextSecondary, fontSize = 12.sp)
            }
        }

        if (!manual) {
            Spacer(Modifier.height(8.dp))
            val t = info?.targets
            TargetLine("热量", t?.kcal, "千卡")
            TargetLine("蛋白质", t?.protein, "g")
            TargetLine("碳水", t?.carbs, "g")
            TargetLine("脂肪", t?.fat, "g")
            val s = info?.suggested
            if (s != null && t != null && kotlin.math.abs(s.kcal - t.kcal) > 1.0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "按当前档案推算应为 " + s.kcal.roundToInt() + " 千卡，当前是手动值。",
                    color = Palette.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(kcal, { kcal = it }, "热量", Modifier.weight(1f))
                NumberField(protein, { protein = it }, "蛋白 g", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(carbs, { carbs = it }, "碳水 g", Modifier.weight(1f))
                NumberField(fat, { fat = it }, "脂肪 g", Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val map = mutableMapOf<String, Double>()
                    kcal.toDoubleOrNull()?.let { map["calories_kcal"] = it }
                    protein.toDoubleOrNull()?.let { map["protein_g"] = it }
                    carbs.toDoubleOrNull()?.let { map["carbs_g"] = it }
                    fat.toDoubleOrNull()?.let { map["fat_g"] = it }
                    vm.saveTargets(map)
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text("保存手动目标", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun TargetLine(label: String, value: Double?, unit: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Palette.TextSecondary, fontSize = 13.sp)
        Text(
            (value?.roundToInt()?.toString() ?: "—") + " " + unit,
            color = Palette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(color = Palette.Surface, shape = RoundedCornerShape(Radii.lg), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text(title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Palette.TextSecondary, fontSize = 12.sp)
        Text(value, color = Palette.TextTertiary, fontSize = 12.sp)
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier,
        shape = RoundedCornerShape(Radii.sm),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            cursorColor = Palette.Accent,
        ),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(Radii.sm))
            .background(if (selected) Palette.Accent.copy(alpha = 0.16f) else Palette.SurfaceHigh)
            .selectable(selected = selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Palette.Accent else Palette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .background(if (selected) Palette.Accent.copy(alpha = 0.14f) else Palette.SurfaceHigh)
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(6.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (selected) Palette.Accent else Color.Transparent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = if (selected) Palette.TextPrimary else Palette.TextSecondary,
            fontSize = 12.5.sp,
        )
    }
}
