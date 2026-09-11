package com.dietcam.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Accent = Color(0xFF3DDC84)
private val CardBg = Color(0xFF1B1B21)
private val Muted = Color(0xFF9BA1A6)

@Composable
fun DietCameraScreen(vm: DietViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.state.collectAsStateWithLifecycle()
    val configVersion by vm.configVersion.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = File(context.cacheDir, "picked_" + System.currentTimeMillis() + ".jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                vm.submit(target)
            }.onFailure { vm.reset() }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        if (!vm.isConfigured()) showSettings = true
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCapture = capture
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                                preview,
                                capture,
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                update = { },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("需要相机权限才能拍照记录饮食", color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予权限")
                }
            }
        }

        // 顶部工具条
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { lensFacing =
                if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            }) {
                Text("🔄", fontSize = 20.sp)
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
            }
        }

        when (val current = state) {
            is DietState.Idle -> CaptureBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                note = noteText,
                onNoteChange = { noteText = it },
                onSendText = {
                    vm.submitText(noteText)
                    noteText = ""
                },
                onCapture = {
                    val capture = imageCapture
                    if (capture == null) {
                        return@CaptureBar
                    }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val file = File(context.cacheDir, "meal_" + stamp + ".jpg")
                    val options = ImageCapture.OutputFileOptions.Builder(file).build()
                    capture.takePicture(
                        options,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                // 输入框里的文字作为这餐的补充说明一起送出去
                                vm.submit(file, noteText.trim())
                                noteText = ""
                            }

                            override fun onError(exception: ImageCaptureException) {
                                vm.reset()
                            }
                        },
                    )
                },
                onPick = { galleryLauncher.launch("image/*") },
            )

            is DietState.Uploading -> Box(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在识别并记录…", color = Color.White)
                }
            }

            is DietState.Success -> ResultCard(
                modifier = Modifier.align(Alignment.BottomCenter),
                result = current.result,
                onRetake = {
                    noteText = ""
                    vm.reset()
                },
            )

            is DietState.Failed -> ErrorCard(
                modifier = Modifier.align(Alignment.BottomCenter),
                message = current.message,
                onDismiss = { vm.reset() },
            )
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
                configVersion = configVersion,
            )
        }
    }
}

@Composable
private fun CaptureBar(
    modifier: Modifier = Modifier,
    note: String,
    onNoteChange: (String) -> Unit,
    onSendText: () -> Unit,
    onCapture: () -> Unit,
    onPick: () -> Unit,
) {
    val canSend = note.isNotBlank()

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 文字输入：不拍照也能直接描述这一餐 ──────────────────────────
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("也可以直接打字，例如：中午吃了一碗牛肉面", fontSize = 14.sp, color = Muted)
            },
            trailingIcon = {
                IconButton(onClick = onSendText, enabled = canSend) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "记录这段文字",
                        tint = if (canSend) Accent else Muted,
                    )
                }
            },
            maxLines = 3,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSendText() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xE61B1B21),
                unfocusedContainerColor = Color(0xE61B1B21),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Color(0xFF3A3A44),
                cursorColor = Accent,
            ),
        )

        Text(
            "拍照，或打字后再按一下 ➤",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )

        // ── 快门 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPick) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "从相册选择", tint = Color.White)
            }
            Spacer(Modifier.width(36.dp))
            Box(
                modifier = Modifier
                    .size(78.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .padding(6.dp),
            ) {
                Button(
                    onClick = onCapture,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                    shape = CircleShape,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
                ) { }
            }
            Spacer(Modifier.width(96.dp))
        }
    }
}

@Composable
private fun MacroChip(label: String, value: Double) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF26262E))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value.roundToInt().toString() + "g", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultCard(
    modifier: Modifier = Modifier,
    result: MealResult,
    onRetake: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(
            modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (result.isFood) (result.meal.ifBlank { "已记录" }) else "未识别到食物",
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(12.dp))
                Spacer(Modifier.weight(1f))
                if (result.isFood) {
                    Text(
                        result.kcal.roundToInt().toString() + " kcal",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(result.title, color = Color.White, fontSize = 16.sp)

            if (result.isFood) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MacroChip("蛋白", result.protein)
                    MacroChip("碳水", result.carbs)
                    MacroChip("脂肪", result.fat)
                }
            }

            if (result.items.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                result.items.take(8).forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("· " + item.name, color = Color.White, fontSize = 14.sp)
                        if (item.portion.isNotBlank()) {
                            Text("  " + item.portion, color = Muted, fontSize = 13.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(item.kcal.roundToInt().toString() + " kcal", color = Muted, fontSize = 13.sp)
                    }
                }
            }

            if (result.advice.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("💡 " + result.advice, color = Color(0xFFFFD479), fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = onRetake,
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
                ) {
                    Text("再拍一张", color = Color(0xFF10231A), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "已记录到当天档案",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    modifier: Modifier = Modifier,
    message: String,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("出问题了", color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                Text("知道了", color = Color(0xFF10231A), fontWeight = FontWeight.Bold)
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
    configVersion: Int,
) {
    var baseUrl by remember(configVersion) { mutableStateOf(current.baseUrl) }
    var apiKey by remember(configVersion) { mutableStateOf(current.apiKey) }
    var secret by remember(configVersion) { mutableStateOf(current.secret) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("AstrBot 地址") },
                    placeholder = { Text("http://1.2.3.4:6185") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OpenAPI Key") },
                    placeholder = { Text("abk_...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("签名密钥") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "签名密钥需与 AstrBot 插件配置里的 hmac_secret 完全一致；API Key 需勾选 plugin 权限。",
                    fontSize = 12.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onTest) { Text("测试连接", color = Accent) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(baseUrl, apiKey, secret) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
