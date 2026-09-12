package com.dietcam.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

@Composable
fun CameraScreen(
    vm: DietViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.capture.collectAsStateWithLifecycle()

    var note by remember { mutableStateOf("") }
    var frozen by remember { mutableStateOf<Bitmap?>(null) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val target = File(context.cacheDir, "picked_" + System.currentTimeMillis() + ".jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                vm.onPhotoCaptured(target)
            }.onFailure { vm.resetCapture() }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 定格图跟随 Reviewing 状态，之后的环节继续展示同一张
    LaunchedEffect(state) {
        when (val s = state) {
            is CaptureUiState.Reviewing -> frozen = s.bitmap
            is CaptureUiState.Live -> frozen = null
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 冻结画面作为底层背景（分析中/结果页都用它压暗做背景）
        val shown = frozen
        if (shown != null) {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(
                    if (state is CaptureUiState.Reviewing) 1f else 0.32f
                ),
            )
        } else if (state is CaptureUiState.Live && hasPermission) {
            CameraPreview(
                lensFacing = lensFacing,
                onReady = { capture = it },
            )
        } else if (state is CaptureUiState.Live) {
            PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
        }

        // 顶部工具条
        TopBar(
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            state = state,
            lensFacing = lensFacing,
            hasPermission = hasPermission,
            onClose = { vm.resetCapture(); onClose() },
            onFlip = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                    CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            },
            onSettings = onOpenSettings,
        )

        // 注意：Crossfade 的内容 lambda 不是 BoxScope，直接在里面写 Modifier.align()
        // 不会作用于外层 Box，曾经把整条底栏顶到屏幕顶部并盖住返回按钮。
        // 这里再套一层铺满的 Box，让里面的 align 真正生效。
        Crossfade(
            targetState = state,
            modifier = Modifier.fillMaxSize(),
            label = "capture",
        ) { current ->
            Box(Modifier.fillMaxSize()) {
            when (current) {
                is CaptureUiState.Live -> LiveBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    note = note,
                    onNoteChange = { note = it },
                    onSendText = {
                        vm.submitText(note)
                        note = ""
                    },
                    onCapture = {
                        capturePicture(context, capture) { file -> vm.onPhotoCaptured(file) }
                    },
                    onPick = { galleryLauncher.launch("image/*") },
                )

                is CaptureUiState.Reviewing -> ReviewBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    note = note,
                    onNoteChange = { note = it },
                    onRetake = { vm.retake() },
                    onConfirm = { vm.confirmPhoto(note.trim()) },
                )

                is CaptureUiState.Analyzing -> AnalyzingPanel(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    streamed = current.streamed,
                    onCancel = { vm.cancelAnalysis() },
                )

                is CaptureUiState.Done -> ResultSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    record = current.record,
                    onAgain = {
                        note = ""
                        vm.resetCapture()
                    },
                    onHome = {
                        note = ""
                        vm.resetCapture()
                        onClose()
                    },
                )

                is CaptureUiState.Failed -> ErrorSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    message = current.message,
                    onRetry = { vm.resetCapture() },
                    onSettings = onOpenSettings,
                )
            }
            }
        }
    }
}

// ------------------------------------------------------------------ 取景

@Composable
private fun CameraPreview(
    lensFacing: Int,
    onReady: (ImageCapture) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                runCatching {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                        preview,
                        imageCapture,
                    )
                    onReady(imageCapture)
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        update = { },
    )

    DisposableEffect(lensFacing) {
        onDispose { runCatching { provider?.unbindAll() } }
    }
}

private fun capturePicture(
    context: android.content.Context,
    capture: ImageCapture?,
    onSaved: (File) -> Unit,
) {
    if (capture == null) return
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    val file = File(context.cacheDir, "meal_" + stamp + ".jpg")
    val options = ImageCapture.OutputFileOptions.Builder(file).build()
    capture.takePicture(
        options,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                onSaved(file)
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
            }
        },
    )
}

// ------------------------------------------------------------------ 组件

@Composable
private fun TopBar(
    modifier: Modifier = Modifier,
    state: CaptureUiState,
    lensFacing: Int,
    hasPermission: Boolean,
    onClose: () -> Unit,
    onFlip: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                if (state is CaptureUiState.Live) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state is CaptureUiState.Live && hasPermission) {
            IconButton(onClick = onFlip) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "切换摄像头", tint = Color.White)
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = Color.White)
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("需要相机权限才能拍照记录饮食", color = Color.White, fontSize = 15.sp)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onGrant,
            shape = RoundedCornerShape(Radii.sm),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
        ) {
            Text("授予权限", color = Palette.OnAccent, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 取景态：输入框 + 相册 + 快门。 */
@Composable
private fun LiveBar(
    modifier: Modifier = Modifier,
    note: String,
    onNoteChange: (String) -> Unit,
    onSendText: () -> Unit,
    onCapture: () -> Unit,
    onPick: () -> Unit,
) {
    val canSend = note.isNotBlank()
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NoteField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = "也可以直接打字，例如：中午吃了一碗牛肉面",
            onSend = onSendText,
            sendEnabled = canSend,
        )
        Text(
            if (canSend) "按 ➤ 直接记录文字，或先拍照再带上这句话" else "拍照，或打字描述这一餐",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPick, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "从相册选择", tint = Color.White)
            }
            Spacer(Modifier.width(30.dp))
            Shutter(onClick = onCapture)
            Spacer(Modifier.width(82.dp))
        }
    }
}

@Composable
private fun Shutter(onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = Color.White,
            onClick = onClick,
        ) {}
    }
}

/** 定格态：确认或重拍。 */
@Composable
private fun ReviewBar(
    modifier: Modifier = Modifier,
    note: String,
    onNoteChange: (String) -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        NoteField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = "补充一句（可选）：这是一人份 / 少油 / 外卖",
            onSend = onConfirm,
            sendEnabled = true,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButtonWide(
                modifier = Modifier.weight(1f),
                text = "重拍",
                icon = Icons.Filled.Refresh,
                onClick = onRetake,
            )
            ButtonWide(
                modifier = Modifier.weight(1f),
                text = "确认",
                icon = Icons.Filled.Check,
                onClick = onConfirm,
            )
        }
    }
}

/** 分析中：展示流式内容，可中断。 */
@Composable
private fun AnalyzingPanel(
    modifier: Modifier = Modifier,
    streamed: String,
    onCancel: () -> Unit,
) {
    val observation = remember(streamed) { streamed.substringBefore("{").trim() }
    val hasJson = streamed.contains("{")

    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 16.dp),
    ) {
        Surface(
            color = Palette.Surface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(Radii.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot()
                    Spacer(Modifier.width(10.dp))
                    Text("正在分析", color = Palette.Accent, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "模型思考中",
                        color = Palette.TextTertiary,
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (observation.isNotEmpty()) {
                    Text(
                        observation,
                        color = Palette.TextPrimary,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                } else {
                    Text("正在读取画面…", color = Palette.TextTertiary, fontSize = 13.sp)
                }

                AnimatedVisibility(visible = hasJson, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = Palette.Accent,
                            strokeWidth = 1.8.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在整理营养数据…", color = Palette.TextTertiary, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButtonWide(
                    modifier = Modifier.fillMaxWidth(),
                    text = "中断",
                    icon = Icons.Filled.Close,
                    onClick = onCancel,
                )
            }
        }
    }
}

/** 结果卡片。 */
@Composable
private fun ResultSheet(
    modifier: Modifier = Modifier,
    record: MealRecord,
    onAgain: () -> Unit,
    onHome: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(14.dp),
    ) {
        Surface(
            color = Palette.Surface,
            shape = RoundedCornerShape(Radii.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (record.isFood) record.meal.ifBlank { "已记录" } else "未识别到食物",
                        color = Palette.Accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (record.isFood) {
                        Text(
                            record.nutrition.kcal.roundToInt().toString(),
                            color = Palette.TextPrimary,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("千卡", color = Palette.TextTertiary, fontSize = 11.sp)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(record.title, color = Palette.TextPrimary, fontSize = 15.sp)

                if (record.isFood) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MacroPill("蛋白", record.nutrition.protein, Palette.Protein)
                        MacroPill("碳水", record.nutrition.carbs, Palette.Carbs)
                        MacroPill("脂肪", record.nutrition.fat, Palette.Fat)
                    }
                }

                if (record.items.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    record.items.take(8).forEach { item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text("· " + item.name, color = Palette.TextPrimary, fontSize = 13.sp)
                            if (item.portion.isNotBlank()) {
                                Text("  " + item.portion, color = Palette.TextTertiary, fontSize = 12.sp)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                item.kcal.roundToInt().toString() + " 千卡",
                                color = Palette.TextTertiary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (record.advice.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text("💡 " + record.advice, color = Palette.Warning, fontSize = 12.5.sp, lineHeight = 19.sp)
                }

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButtonWide(
                        modifier = Modifier.weight(1f),
                        text = "再拍一张",
                        icon = Icons.Filled.Refresh,
                        onClick = onAgain,
                    )
                    ButtonWide(
                        modifier = Modifier.weight(1f),
                        text = "回主页",
                        icon = null,
                        onClick = onHome,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorSheet(
    modifier: Modifier = Modifier,
    message: String,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(14.dp),
    ) {
        Surface(
            color = Palette.Surface,
            shape = RoundedCornerShape(Radii.lg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("出问题了", color = Palette.Danger, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(message, color = Palette.TextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButtonWide(
                        modifier = Modifier.weight(1f),
                        text = "检查设置",
                        icon = Icons.Filled.Settings,
                        onClick = onSettings,
                    )
                    ButtonWide(
                        modifier = Modifier.weight(1f),
                        text = "重来",
                        icon = null,
                        onClick = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroPill(label: String, value: Double, color: Color) {
    Column(
        Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = color, fontSize = 10.sp)
        Text(
            value.roundToInt().toString() + "g",
            color = Palette.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, fontSize = 13.sp, color = Palette.TextTertiary) },
        trailingIcon = {
            IconButton(onClick = onSend, enabled = sendEnabled) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "记录",
                    tint = if (sendEnabled) Palette.Accent else Palette.TextTertiary,
                )
            }
        },
        maxLines = 3,
        shape = RoundedCornerShape(Radii.md),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Palette.Surface.copy(alpha = 0.94f),
            unfocusedContainerColor = Palette.Surface.copy(alpha = 0.94f),
            focusedTextColor = Palette.TextPrimary,
            unfocusedTextColor = Palette.TextPrimary,
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Outline,
            cursorColor = Palette.Accent,
        ),
    )
}

@Composable
private fun ButtonWide(
    modifier: Modifier = Modifier,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(Radii.sm),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.OnAccent, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Palette.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun OutlinedButtonWide(
    modifier: Modifier = Modifier,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(Radii.sm),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Outline),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Palette.TextSecondary, fontSize = 14.sp)
    }
}

/** 一闪一闪的小圆点，表示正在工作。 */
@Composable
private fun PulsingDot() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(Palette.Accent.copy(alpha = alpha)),
    )
}