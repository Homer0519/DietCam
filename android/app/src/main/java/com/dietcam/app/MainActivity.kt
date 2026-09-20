package com.dietcam.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: DietViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DietCamTheme {
                Surface(color = Palette.Background) {
                    DietCamApp(vm)
                }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("主页", Icons.Filled.Home),
    Calendar("日历", Icons.Filled.CalendarMonth),
    Memories("回忆", Icons.Filled.History),
    Profile("我的", Icons.Filled.Person),
}

@Composable
private fun DietCamApp(vm: DietViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val notice by vm.notice.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val askProfile by vm.askProfile.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (!vm.isConfigured()) showSettings = true
        vm.refreshHome()
        vm.refreshProfile()
        // 一天最多查一次，静默失败也不打扰
        vm.checkUpdateIfDue()
    }

    LaunchedEffect(tab) {
        when (tab) {
            Tab.Home -> vm.refreshHome()
            Tab.Calendar -> vm.refreshCalendar(java.time.YearMonth.now().toString())
            Tab.Memories -> vm.refreshHistory()
            Tab.Profile -> vm.refreshProfile()
        }
    }

    BackHandler(enabled = showCamera) {
        vm.resetCapture()
        showCamera = false
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        AnimatedContent(
            targetState = showCamera,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160)))
                } else {
                    (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(160)))
                }
            },
            label = "camera",
        ) { camera ->
            if (camera) {
                CameraScreen(
                    vm = vm,
                    onClose = { showCamera = false },
                    onOpenSettings = { showSettings = true },
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    // 顶部统一避让状态栏与刘海：日历 / 回忆 / 我的 三个页面自己不带内边距，
                    // 曾经标题直接画到刘海底下被挡住。底部导航栏自己处理底部安全区，
                    // 相机页走另一条分支（画面要铺满），不受这里影响。
                    Box(
                        Modifier
                            .weight(1f)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
                    ) {
                        when (tab) {
                            Tab.Home -> HomeScreen(
                                vm = vm,
                                onOpenCamera = { showCamera = true },
                                onOpenSettings = { showSettings = true },
                            )

                            Tab.Calendar -> CalendarScreen(vm)
                            Tab.Memories -> MemoriesScreen(vm)
                            Tab.Profile -> ProfileScreen(vm, onOpenSettings = { showSettings = true })
                        }
                    }
                    BottomNav(current = tab, onSelect = { tab = it })
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            current = vm.settings(),
            models = models,
            onFetchModels = { vm.fetchModels(it) },
            onCloseModels = { vm.closeModels() },
            onDismiss = { showSettings = false },
            onSave = { settings ->
                vm.saveSettings(settings)
                showSettings = false
            },
            onTest = { settings -> vm.testConnection(settings) },
            onSelfCheck = { vm.localSelfCheck() },
        )
    }

    notice?.let { n ->
        ResultDialog(
            title = n.title,
            message = n.message,
            onDismiss = { vm.dismissNotice() },
        )
    }

    update?.let { info ->
        UpdateDialog(
            info = info,
            current = BuildConfig.VERSION_NAME,
            onDownload = { vm.downloadUpdate() },
            onLater = { vm.dismissUpdate() },
        )
    }

    if (askProfile) {
        ProfileSetupDialog(
            busy = busy,
            onSave = { profile ->
                vm.saveProfile(profile) { vm.dismissAskProfile() }
            },
            onLater = { vm.dismissAskProfile() },
        )
    }
}

/**
 * 第一次配置完连接后弹一次：先填身高体重，否则首页的「今日目标」算不出来。
 * 活动量默认「轻度」，之后在「我的」页随时能改。
 */
@Composable
private fun ProfileSetupDialog(
    busy: Boolean,
    onSave: (BodyProfile) -> Unit,
    onLater: () -> Unit,
) {
    var height by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("male") }
    var goal by remember { mutableStateOf("maintain") }

    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("填一下身体数据", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "用来按 Mifflin-St Jeor 公式推算每天的摄入目标。\n" +
                        "填错也没关系，「我的」页随时能改；活动量先按「轻度」算。",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                Field(height, { height = it }, "身高 cm", "170")
                Spacer(Modifier.height(10.dp))
                Field(weight, { weight = it }, "体重 kg", "65")
                Spacer(Modifier.height(10.dp))
                Field(age, { age = it }, "年龄", "30")
                Spacer(Modifier.height(14.dp))
                Text("性别", color = Palette.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(Palette.SurfaceHigh),
                ) {
                    ModeChip("男", sex == "male", Modifier.weight(1f)) { sex = "male" }
                    ModeChip("女", sex == "female", Modifier.weight(1f)) { sex = "female" }
                }
                Spacer(Modifier.height(14.dp))
                Text("目标", color = Palette.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(Palette.SurfaceHigh),
                ) {
                    ModeChip("减脂", goal == "lose", Modifier.weight(1f)) { goal = "lose" }
                    ModeChip("维持", goal == "maintain", Modifier.weight(1f)) { goal = "maintain" }
                    ModeChip("增重", goal == "gain", Modifier.weight(1f)) { goal = "gain" }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        BodyProfile(
                            heightCm = height.toDoubleOrNull() ?: 170.0,
                            weightKg = weight.toDoubleOrNull() ?: 65.0,
                            age = age.toIntOrNull() ?: 30,
                            sex = sex,
                            activity = "light",
                            goal = goal,
                        ),
                    )
                },
                enabled = !busy,
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text(
                    if (busy) "保存中…" else "保存",
                    color = Palette.OnAccent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("以后再说", color = Palette.TextSecondary)
            }
        },
    )
}

@Composable
private fun UpdateDialog(
    info: UpdateInfo,
    current: String,
    onDownload: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("发现新版本 " + info.version, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "当前版本 " + current + " → 新版本 " + info.version,
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "下载会在后台进行，完成后点通知栏里的那一项即可安装。",
                    color = Palette.TextTertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDownload,
                shape = RoundedCornerShape(Radii.sm),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
            ) {
                Text("下载更新", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater) {
                Text("稍后", color = Palette.TextSecondary)
            }
        },
    )
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(color = Palette.Surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { item ->
                val selected = item == current
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radii.sm))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (selected) Palette.Accent else Palette.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = if (selected) Palette.Accent else Palette.TextTertiary,
                        fontSize = 10.5.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    current: DietSettings,
    onDismiss: () -> Unit,
    onSave: (DietSettings) -> Unit,
    onTest: (DietSettings) -> Unit,
    onSelfCheck: () -> Unit,
    models: ModelPickState?,
    onFetchModels: (DietSettings) -> Unit,
    onCloseModels: () -> Unit,
) {
    var local by remember { mutableStateOf(current.localMode) }
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var secret by remember { mutableStateOf(current.secret) }
    var modelBase by remember { mutableStateOf(current.modelBaseUrl) }
    var modelKey by remember { mutableStateOf(current.modelApiKey) }
    var modelName by remember { mutableStateOf(current.modelName) }

    fun collected() = DietSettings(
        baseUrl = baseUrl,
        apiKey = apiKey,
        secret = secret,
        localMode = local,
        modelBaseUrl = modelBase,
        modelApiKey = modelKey,
        modelName = modelName,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ModeChooser(local) { local = it }
                Spacer(Modifier.height(14.dp))

                if (local) {
                    Field(modelBase, { modelBase = it }, "模型接口地址", "https://api.example.com/v1")
                    Spacer(Modifier.height(10.dp))
                    Field(modelKey, { modelKey = it }, "模型 API Key", "sk-...")
                    Spacer(Modifier.height(10.dp))
                    Field(modelName, { modelName = it }, "模型名称", "qwen2.5-vl-7b-instruct")
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { onFetchModels(collected()) },
                            enabled = !(models?.loading ?: false),
                        ) {
                            if (models?.loading == true) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Palette.Accent,
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                if (models?.loading == true) "拉取中…" else "拉取模型列表",
                                color = if (models?.loading == true) Palette.TextTertiary else Palette.Accent,
                                fontSize = 13.sp,
                            )
                        }
                        if (models != null && !models.loading && models.options.isNotEmpty()) {
                            Text(
                                "已拉取 " + models.options.size + " 个",
                                color = Palette.TextTertiary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    models?.error?.let { message ->
                        Text(message, color = Palette.Danger, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onTest(collected()) }) {
                            Text("测试连接", color = Palette.Accent)
                        }
                        TextButton(onClick = onSelfCheck) {
                            Text("自检（看看存到哪了）", color = Palette.TextSecondary, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    HintBox(
                        "本地模式：不需要 AstrBot。\n" +
                            "分析直接调用上面这个 OpenAI 兼容的视觉模型，\n" +
                            "照片与记录都留在这台手机上（换手机看不到）。\n\n" +
                            "· 地址写到 /v1 就行，程序会自动补 /chat/completions\n" +
                            "· 模型必须能看图：qwen2.5-vl / gpt-4o / glm-4v 等\n" +
                            "· 数据在 App 私有目录，卸载即清空，注意备份",
                    )
                } else {
                    Field(baseUrl, { baseUrl = it }, "AstrBot 地址", "http://1.2.3.4:6185")
                    Spacer(Modifier.height(10.dp))
                    Field(apiKey, { apiKey = it }, "AstrBot API Key", "abk_...")
                    Spacer(Modifier.height(10.dp))
                    Field(secret, { secret = it }, "签名密钥 (hmac_secret)", "与插件配置一致")
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { onTest(collected()) }) {
                        Text("测试连接", color = Palette.Accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    HintBox(
                        "① AstrBot API Key：AstrBot 自己的钥匙，在网页端\n" +
                            "   设置 → API Key 新建，权限勾 plugin，形如 abk_xxx\n" +
                            "   ⚠️ 不是 OpenAI / 模型的 key\n\n" +
                            "② 签名密钥：与插件配置里的 hmac_secret 填一样的随机串\n\n" +
                            "一个是「能不能进 AstrBot 的门」，\n" +
                            "一个是「插件认不认你」。\n\n" +
                            "照片与记录都存在 AstrBot 那边，手机只保留一份缓存。",
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(collected()) },
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

    // 拉取成功后弹一个列表让用户挑，省得手打模型名
    val picked = models
    if (picked != null && !picked.loading && picked.options.isNotEmpty()) {
        ModelPickerDialog(
            options = picked.options,
            current = modelName,
            onPick = {
                modelName = it
                onCloseModels()
            },
            onDismiss = onCloseModels,
        )
    }
}

/** 拉取到的模型列表，点一个填进「模型名称」。 */
@Composable
private fun ModelPickerDialog(
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val shown = remember(options, keyword) {
        val key = keyword.trim()
        if (key.isEmpty()) options else options.filter { it.contains(key, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("选择模型", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(
                    "共 " + options.size + " 个，当前：" + current.ifBlank { "未选" },
                    color = Palette.TextTertiary,
                    fontSize = 11.sp,
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    label = { Text("搜索", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Radii.sm),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Palette.Accent,
                        unfocusedBorderColor = Palette.Outline,
                        cursorColor = Palette.Accent,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (shown.isEmpty()) {
                        Text("没有匹配的模型", color = Palette.TextSecondary, fontSize = 13.sp)
                    } else {
                        shown.forEach { name ->
                            val selected = name == current
                            Surface(
                                color = if (selected) Palette.AccentDim else Color.Transparent,
                                shape = RoundedCornerShape(Radii.sm),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onPick(name) },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        name,
                                        color = if (selected) Palette.TextPrimary else Palette.TextSecondary,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = Palette.Accent,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.TextSecondary) }
        },
    )
}

/** 两种模式二选一。 */
@Composable
private fun ModeChooser(local: Boolean, onChange: (Boolean) -> Unit) {
    Column {
        Text("运行方式", color = Palette.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.sm))
                .background(Palette.SurfaceHigh)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModeChip("连 AstrBot", !local, Modifier.weight(1f)) { onChange(false) }
            ModeChip("本地模式", local, Modifier.weight(1f)) { onChange(true) }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(if (selected) Palette.Accent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Palette.OnAccent else Palette.TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** 灰色说明块。 */
@Composable
private fun HintBox(text: String) {
    Surface(
        color = Palette.SurfaceHigh,
        shape = RoundedCornerShape(Radii.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 13.sp) },
        placeholder = { Text(placeholder, fontSize = 13.sp, color = Palette.TextTertiary) },
        singleLine = true,
        shape = RoundedCornerShape(Radii.sm),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            cursorColor = Palette.Accent,
        ),
    )
}

@Composable
private fun ResultDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
) {
    val ok = title.contains("成功") || title.contains("已")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                title,
                color = if (ok) Palette.Accent else Palette.Danger,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(message, fontSize = 13.sp, lineHeight = 20.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (ok) "好" else "知道了", color = Palette.Accent)
            }
        },
    )
}