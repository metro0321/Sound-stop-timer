package com.example.soundstop

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

private val Paper = Color(0xFFF1F0E9)
private val Ink = Color(0xFF19352C)
private val MutedInk = Color(0xFF718078)
private val Accent = Color(0xFFE66A45)
private val Signal = Color(0xFFB5D95C)
private val Panel = Color(0xFF19352C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val soundMonitor = BluetoothSoundMonitor(this)
        setContent {
            MaterialTheme {
                SoundStopScreen(
                    soundMonitor = soundMonitor,
                    onKeepScreenOn = { keepOn ->
                        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                )
            }
        }
    }
}

@Composable
private fun SoundStopScreen(
    soundMonitor: BluetoothSoundMonitor,
    onKeepScreenOn: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isRunning by remember { mutableStateOf(false) }
    var isStopped by remember { mutableStateOf(false) }
    var elapsedBeforeStart by remember { mutableLongStateOf(0L) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var threshold by remember { mutableFloatStateOf(-45f) }
    var holdDurationMs by remember { mutableLongStateOf(300L) }
    var level by remember { mutableFloatStateOf(-90f) }
    var micName by remember { mutableStateOf("未接続") }
    var inputDevices by remember { mutableStateOf(emptyList<SoundInputDevice>()) }
    var selectedInput by remember { mutableStateOf<SoundInputDevice?>(null) }
    var isInputMenuExpanded by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("入力機器を選択して計測を開始") }

    fun refreshInputDevices() {
        inputDevices = soundMonitor.availableInputs()
        selectedInput = inputDevices.firstOrNull { it.id == selectedInput?.id }
            ?: inputDevices.firstOrNull()
    }

    fun stopTimer(reason: String) {
        if (isRunning) elapsed = elapsedBeforeStart + (SystemClock.elapsedRealtime() - startedAt)
        isRunning = false
        isStopped = true
        message = reason
        soundMonitor.stop()
    }

    fun beginMonitoring() {
        val input = selectedInput
        if (input == null) {
            message = "USBまたはBluetoothのマイクを接続してください"
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        elapsedBeforeStart = 0L
        elapsed = 0L
        startedAt = SystemClock.elapsedRealtime()
        isStopped = false
        isRunning = true
        level = -90f
        message = "音を待っています"
        soundMonitor.start(
            thresholdDbfs = threshold,
            holdDurationMs = holdDurationMs,
            selectedInputId = input.id,
            onLevel = { nextLevel, deviceName ->
                level = nextLevel
                micName = deviceName
            },
            onTriggered = { stopTimer("音を検知して停止") },
            onError = { error -> stopTimer(error) }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val audioGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        val bluetoothGranted = grants[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (audioGranted && (selectedInput?.isBluetooth != true || bluetoothGranted)) {
            beginMonitoring()
        } else {
            message = if (selectedInput?.isBluetooth == true) {
                "マイクとBluetoothの権限を許可してください"
            } else {
                "マイクの権限を許可してください"
            }
        }
    }

    fun requestStart() {
        val input = selectedInput
        if (input == null) {
            message = "USBまたはBluetoothのマイクを接続してください"
            return
        }
        val permissions = if (input.isBluetooth) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        ) beginMonitoring() else permissionLauncher.launch(permissions)
    }

    SideEffect { onKeepScreenOn(isRunning) }

    LaunchedEffect(Unit) { refreshInputDevices() }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            elapsed = elapsedBeforeStart + (SystemClock.elapsedRealtime() - startedAt)
            delay(30)
        }
    }

    DisposableEffect(lifecycleOwner, isRunning) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && isRunning) {
                stopTimer("アプリを離れたため停止")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "FIELD TIMER  /  01",
                        color = MutedInk,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Sound Stop Timer",
                        color = Ink,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(if (isRunning) Signal else MutedInk, CircleShape)
                    )
                    Text(
                        if (isRunning) "LIVE" else "READY",
                        color = Ink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("ELAPSED TIME", color = Color(0xFFB9C8BF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text("${formatHundredths(elapsed)}", color = Signal, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Text(
                    formatClock(elapsed),
                    color = Color(0xFFF5F2E9),
                    fontSize = 52.sp,
                    lineHeight = 58.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light,
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).background(if (isRunning) Signal else Accent, CircleShape))
                    Text(message, color = Color(0xFFD5DED8), fontSize = 13.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        Text("INPUT DEVICE", color = MutedInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Box {
                            TextButton(
                                onClick = { isInputMenuExpanded = true },
                                enabled = !isRunning && inputDevices.isNotEmpty(),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text(
                                    selectedInput?.let {
                                        "${it.connectionLabel} · ${if (isRunning) micName else it.name}"
                                    }
                                        ?: "入力機器が未検出",
                                    color = Ink,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = isInputMenuExpanded,
                                onDismissRequest = { isInputMenuExpanded = false }
                            ) {
                                inputDevices.forEach { input ->
                                    DropdownMenuItem(
                                        text = { Text("${input.connectionLabel} · ${input.name}") },
                                        onClick = {
                                            selectedInput = input
                                            isInputMenuExpanded = false
                                        },
                                        enabled = !isRunning
                                    )
                                }
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            onClick = { refreshInputDevices() },
                            enabled = !isRunning,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("更新", color = Ink, fontSize = 12.sp)
                        }
                        Text("${level.toInt()} dBFS", color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                LevelMeter(levelDbfs = level, thresholdDbfs = threshold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("-90", color = MutedInk, fontSize = 10.sp)
                    Text("しきい値  ${threshold.toInt()} dBFS", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("0", color = MutedInk, fontSize = 10.sp)
                }
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    onValueChangeFinished = {},
                    valueRange = -90f..0f,
                    enabled = !isRunning,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFFD5D8CF)
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("停止まで  ${holdDurationMs}ms", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("50–1000ms", color = MutedInk, fontSize = 10.sp)
                }
                Slider(
                    value = holdDurationMs.toFloat(),
                    onValueChange = { holdDurationMs = it.toLong() },
                    valueRange = 50f..1000f,
                    steps = 18,
                    enabled = !isRunning,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFFD5D8CF)
                    )
                )
                Text(
                    "閾値を下げると小さな音に反応し、時間を短くすると短い音でも停止します。",
                    color = MutedInk,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isRunning) stopTimer("手動で停止") else requestStart()
                    },
                    modifier = Modifier.weight(1f).height(58.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) Ink else Accent,
                        contentColor = Color.White
                    )
                ) {
                    Text(if (isRunning) "停止する" else "計測を開始", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = {
                        soundMonitor.stop()
                        isRunning = false
                        isStopped = false
                        elapsedBeforeStart = 0L
                        elapsed = 0L
                        level = -90f
                        micName = "未接続"
                        message = "入力機器を選択して計測を開始"
                    },
                    enabled = !isRunning,
                    modifier = Modifier.height(54.dp)
                ) {
                    Text("リセット", color = if (isRunning) MutedInk else Ink, fontWeight = FontWeight.SemiBold)
                }
            }
            if (isStopped) {
                Text(
                    "計測結果  ${formatClock(elapsed)}.${(elapsed % 1000 / 10).toString().padStart(2, '0')}",
                    modifier = Modifier.align(Alignment.End),
                    color = MutedInk,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(levelDbfs: Float, thresholdDbfs: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
    ) {
        val meterWidth = size.width
        val centerY = size.height / 2f
        val thresholdX = ((thresholdDbfs + 90f) / 90f).coerceIn(0f, 1f) * meterWidth
        val levelX = ((levelDbfs + 90f) / 90f).coerceIn(0f, 1f) * meterWidth
        drawLine(Color(0xFFD9DCD3), start = androidx.compose.ui.geometry.Offset(0f, centerY), end = androidx.compose.ui.geometry.Offset(meterWidth, centerY), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent.copy(alpha = 0.45f), start = androidx.compose.ui.geometry.Offset(0f, centerY), end = androidx.compose.ui.geometry.Offset(levelX, centerY), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Accent, start = androidx.compose.ui.geometry.Offset(thresholdX, centerY - 10.dp.toPx()), end = androidx.compose.ui.geometry.Offset(thresholdX, centerY + 10.dp.toPx()), strokeWidth = 2.dp.toPx())
        drawCircle(Color.White, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(levelX, centerY))
        drawCircle(Accent, radius = 3.dp.toPx(), center = androidx.compose.ui.geometry.Offset(levelX, centerY))
    }
}

private fun formatClock(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%02d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun formatHundredths(milliseconds: Long): String =
    ".%02d".format(milliseconds % 1000 / 10)