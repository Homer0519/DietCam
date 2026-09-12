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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

    LaunchedEffect(Unit) {
        if (!vm.isConfigured()) showSettings = true
        vm.refreshHome()
        vm.refreshProfile()
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
            onDismiss = { showSettings = false },
            onSave = { baseUrl, apiKey, secret ->
                vm.saveSettings(baseUrl, apiKey, secret)
                showSettings = false
            },
            onTest = { vm.testConnection() },
        )
    }

    notice?.let { n ->
        ResultDialog(
            title = n.title,
            message = n.message,
            onDismiss = { vm.dismissNotice() },
        )
    }
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
    onSave: (String, String, String) -> Unit,
    onTest: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var secret by remember { mutableStateOf(current.secret) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Field(baseUrl, { baseUrl = it }, "AstrBot 地址", "http://1.2.3.4:6185")
                Spacer(Modifier.height(10.dp))
                Field(apiKey, { apiKey = it }, "AstrBot API Key", "abk_...")
                Spacer(Modifier.height(10.dp))
                Field(secret, { secret = it }, "签名密钥 (hmac_secret)", "与插件配置一致")
                Spacer(Modifier.height(14.dp))
                Surface(
                    color = Palette.SurfaceHigh,
                    shape = RoundedCornerShape(Radii.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "① AstrBot API Key：AstrBot 自己的钥匙，在网页端\n" +
                            "   设置 → API Key 新建，权限勾 plugin，形如 abk_xxx\n" +
                            "   ⚠️ 不是 OpenAI / 模型的 key\n\n" +
                            "② 签名密钥：与插件配置里的 hmac_secret 填一样的随机串\n\n" +
                            "一个是「能不能进 AstrBot 的门」，\n" +
                            "一个是「插件认不认你」。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Palette.TextSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onTest) { Text("测试连接", color = Palette.Accent) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(baseUrl, apiKey, secret) },
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