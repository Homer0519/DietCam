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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

private enum class Screen { Home, Camera }

@Composable
private fun DietCamApp(vm: DietViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var showSettings by remember { mutableStateOf(false) }
    val notice by vm.notice.collectAsStateWithLifecycle()

    // 首次进入且未配置时，直接引导去设置
    LaunchedEffect(Unit) {
        if (!vm.isConfigured()) showSettings = true
        vm.refreshHome()
    }

    // 从相机回到主页时刷新一下数据
    LaunchedEffect(screen) {
        if (screen == Screen.Home) vm.refreshHome()
    }

    BackHandler(enabled = screen == Screen.Camera) {
        vm.resetCapture()
        screen = Screen.Home
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (targetState == Screen.Camera) {
                (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160)))
            } else {
                (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { it / 3 } + fadeOut(tween(160)))
            }
        },
        label = "screen",
    ) { target ->
        when (target) {
            Screen.Home -> HomeScreen(
                vm = vm,
                onOpenCamera = { screen = Screen.Camera },
                onOpenSettings = { showSettings = true },
            )

            Screen.Camera -> CameraScreen(
                vm = vm,
                onClose = { screen = Screen.Home },
                onOpenSettings = { showSettings = true },
            )
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
                Field(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = "AstrBot 地址",
                    placeholder = "http://1.2.3.4:6185",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = "AstrBot API Key",
                    placeholder = "abk_...",
                )
                Spacer(Modifier.height(10.dp))
                Field(
                    value = secret,
                    onValueChange = { secret = it },
                    label = "签名密钥 (hmac_secret)",
                    placeholder = "与插件配置一致",
                )
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
                TextButton(onClick = onTest) {
                    Text("测试连接", color = Palette.Accent)
                }
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
            TextButton(onClick = onDismiss) {
                Text("取消", color = Palette.TextSecondary)
            }
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
    val ok = title.contains("成功")
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
                Text(if (ok) "好，开始用" else "知道了", color = Palette.Accent)
            }
        },
    )
}
