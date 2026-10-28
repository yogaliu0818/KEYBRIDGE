package com.keybridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var imported by mutableStateOf("")
    private var shareSequence by mutableIntStateOf(0)
    private lateinit var transport: Transport
    private var status by mutableStateOf("請先在 Windows 設定中完成藍牙配對")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = Transport(this) { status = it }
        if (intent?.action == Intent.ACTION_SEND) imported = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF80D8FF))) {
                var text by rememberSaveable { mutableStateOf(imported) }
                var simulation by remember { mutableStateOf(false) }
                var simOutcome by remember { mutableStateOf(0) }
                val prefs = remember { getSharedPreferences("settings", MODE_PRIVATE) }
                var light by remember { mutableStateOf(prefs.getBoolean("light", false)) }
                var autoClear by remember { mutableStateOf(prefs.getBoolean("clear", false)) }
                var hidDelay by remember { mutableIntStateOf(prefs.getInt("hidDelay", 30)) }
                LaunchedEffect(shareSequence) { if (shareSequence > 0) { text = if (text.isEmpty()) imported else text + "\n" + imported; status = "已加入分享的文字，請確認內容" } }
                var settings by remember { mutableStateOf(false) }
                var pin by remember { mutableStateOf("") }
                var mode by remember { mutableStateOf(false) }
                var trusted by remember { mutableStateOf(transport.isTrusted()) }
                var busy by remember { mutableStateOf(false) }
                var devices by remember { mutableStateOf(emptyList<android.bluetooth.BluetoothDevice>()) }
                var selected by remember { mutableStateOf<android.bluetooth.BluetoothDevice?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    val canReadBluetooth = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    if (canReadBluetooth && transport.isTrusted()) {
                        runCatching { transport.savedDevice() }.getOrNull()?.let { saved ->
                            selected = saved
                            devices = transport.devices()
                            busy = true
                            status = "正在自動連線至已記住的電腦…"
                            status = transport.connect(saved, "")
                            trusted = transport.isTrusted()
                            busy = false
                        }
                    }
                }
                BackHandler(enabled = settings) { settings = false }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) { devices = transport.devices(); status = "請選擇已配對的電腦" } else status = "需要附近裝置權限才能使用藍牙"
                }
                MaterialTheme(colorScheme = if (light) lightColorScheme(primary = Color(0xFF006D87)) else darkColorScheme(primary = Color(0xFFD1F13C), background = Color(0xFF05080A), surface = Color(0xFF101519), surfaceVariant = Color(0xFF161C1F), onSurface = Color(0xFFF0F3F2))) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().background(if (light) Brush.verticalGradient(listOf(Color(0xFFF3F9FB), Color(0xFFFFFFFF))) else Brush.verticalGradient(listOf(Color(0xFF11171B), Color(0xFF05090B), Color(0xFF06090B)))).safeDrawingPadding().imePadding().padding(horizontal = 22.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BridgeMark(Modifier.size(width = 62.dp, height = 42.dp))
                                Column { Text(if (settings) "設定" else "KEYBRIDGE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Light, letterSpacing = 3.sp); if (!settings) Text("MOBILE INPUT · CONNECT TO PC", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .48f)) }
                            }
                            TextButton(onClick = { settings = !settings }) { Text(if (settings) "返回" else "⚙", fontSize = if (settings) 14.sp else 24.sp) }
                        }
                        if (settings) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("我的電腦", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (trusted) "已記住這台電腦。下次開啟 App 會自動嘗試連線；傳送前仍會等你按下「傳送」。" else "第一次使用：先在 Windows 藍牙設定中與手機配對，再輸入 Windows 顯示的代碼。成功後會記住這台電腦。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(enabled = !busy, onClick = {
                                    if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                    else { devices = transport.devices(); status = if (transport.available()) "請選擇已配對的電腦" else "請開啟藍牙" }
                                }) { Text("選擇已配對裝置") }
                                devices.forEach { d -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = selected == d, onClick = { selected = d }, enabled = !busy); Text(transport.deviceName(d)) } }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(selected = !mode, onClick = { mode = false; transport.stop(); status = "已切換完整文字模式" }, label = { Text("完整文字") }, enabled = !busy)
                                    FilterChip(selected = mode, onClick = { mode = true; transport.stop(); status = "已切換藍牙鍵盤模式" }, label = { Text("藍牙鍵盤") }, enabled = !busy)
                                }
                                Text(if (mode) "支援英文和基本符號，無法直接傳送中文。" else "支援中文及 Emoji，需要 Windows 接收程式。", style = MaterialTheme.typography.bodySmall)
                                if (!mode && !trusted) OutlinedTextField(pin, { pin = it.filter { c -> c in '0'..'9' }.take(6) }, label = { Text("第一次連線：Windows 顯示的 6 位數代碼") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = selected != null && !busy && !simulation, onClick = { val d = selected ?: return@Button; if (mode) transport.startHid(d) else scope.launch { busy = true; status = "正在連線…"; try { status = transport.connect(d, pin); trusted = transport.isTrusted() } finally { busy = false } } }) { Text(if (trusted) "重新連線" else "第一次配對") }
                                    OutlinedButton(enabled = !busy, onClick = { transport.stop(); status = "已斷線" }) { Text("斷線") }
                                }
                                if (trusted && !mode) TextButton(enabled = !busy, onClick = { transport.forgetTrust(); trusted = false; selected = null; pin = ""; status = "已清除手機端信任，請在 Windows 也按「解除信任」後重新配對" }) { Text("解除信任／重新配對") }
                                Text(status, color = MaterialTheme.colorScheme.primary)
                                HorizontalDivider()
                                Text("一般", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("淺色模式"); Switch(light, { light = it; prefs.edit().putBoolean("light", it).apply() }) }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("送出後清空文字"); Switch(autoClear, { autoClear = it; prefs.edit().putBoolean("clear", it).apply() }) }
                                HorizontalDivider()
                                Text("進階與測試", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("離線模擬"); Switch(simulation, { simulation = it; transport.stop(); status = if (it) "模擬模式：不會傳送至電腦" else "尚未連線" }, enabled = !busy) }
                                if (simulation) { Text("模擬結果不代表實際連線成功", style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("成功", "失敗", "中斷").forEachIndexed { i, label -> FilterChip(selected = simOutcome == i, onClick = { simOutcome = i }, label = { Text(label) }) } } }
                                Text("鍵盤速度（按鍵間隔）", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(10, 30, 60).forEach { speed -> FilterChip(selected = hidDelay == speed, onClick = { hidDelay = speed; prefs.edit().putInt("hidDelay", speed).apply() }, label = { Text("${speed}ms") }) } }
                                Text("背景常駐與即時同步尚未完成；重新開啟 App 時會自動嘗試一次。", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(20.dp))
                            }
                        } else {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                val connected = !simulation && transport.connected(mode)
                                val frame = techShape(18f)
                                Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), frame).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .8f), MaterialTheme.colorScheme.surface.copy(alpha = .94f))), frame).padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TechIcon("phone", Modifier.size(24.dp), MaterialTheme.colorScheme.onSurface)
                                    Text("···", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.padding(horizontal = 4.dp))
                                    TechIcon("bluetooth", Modifier.size(25.dp), MaterialTheme.colorScheme.onSurface)
                                    Text("···", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), modifier = Modifier.padding(horizontal = 4.dp))
                                    TechIcon("computer", Modifier.size(27.dp), MaterialTheme.colorScheme.onSurface)
                                    Text(if (simulation) "模擬模式" else if (connected) "已連線" else "尚未連接電腦", modifier = Modifier.weight(1f).padding(start = 9.dp), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    TextButton(onClick = { settings = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (connected) "管理 ›" else "連接 ›", color = MaterialTheme.colorScheme.primary) }
                                }
                                Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .5f), techShape(20f)).background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .66f), MaterialTheme.colorScheme.background.copy(alpha = .95f))), techShape(20f))) {
                                    Box(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(horizontal = 17.dp, vertical = 18.dp)) {
                                        if (text.isEmpty()) Text("輸入或貼上文字…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .42f), style = MaterialTheme.typography.bodyLarge)
                                        BasicTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxSize(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                                        OutlinedButton(enabled = !busy && (simulation || transport.connected(mode)), shape = techShape(12f), onClick = {
                                            val isSim = simulation; val outcome = simOutcome; val hidMode = mode
                                            scope.launch {
                                                busy = true; status = "正在送出 Enter…"
                                                val result = if (isSim) { delay(350); when (outcome) { 0 -> "模擬已送出 Enter；沒有傳送至電腦"; 1 -> "模擬 Enter 失敗"; else -> "模擬連線中斷，結果不明" } }
                                                    else if (hidMode) transport.sendHid("\n", hidDelay.toLong()) else transport.sendText("\n")
                                                status = when {
                                                    !isSim && result.startsWith("HID 按鍵事件已送出") -> "Enter 按鍵事件已送出"
                                                    !isSim && result.startsWith("電腦已接收") -> "電腦已接收並送出 Enter 事件"
                                                    else -> result
                                                }
                                                busy = false
                                            }
                                        }) { Text("↵  Enter", fontWeight = FontWeight.SemiBold) }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .2f))
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.weight(1f))
                                        Text("${text.codePointCount(0, text.length)} 字", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(12.dp))
                                        TextButton(enabled = text.isNotEmpty() && !busy, onClick = { text = "" }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("✕", fontSize = 20.sp) }
                                    }
                                }
                                Text(status, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(8.dp))
                            }
                            Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).height(54.dp), shape = techShape(20f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCCE956), contentColor = Color(0xFF11180A), disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f)), enabled = !busy && text.isNotEmpty() && (simulation || transport.connected(mode)), onClick = {
                                val snapshot = text; val hidMode = mode; val isSim = simulation; val outcome = simOutcome
                                scope.launch { busy = true; status = "傳送中…"; status = if (isSim) { delay(600); when(outcome) { 0 -> "模擬成功：沒有向電腦傳送"; 1 -> "模擬失敗：文字已保留"; else -> "模擬中斷：文字已保留" } } else if (hidMode) transport.sendHid(snapshot, hidDelay.toLong()) else transport.sendText(snapshot); if (autoClear && text == snapshot && ((isSim && outcome == 0) || (!isSim && (status.startsWith("電腦已接收") || status.startsWith("HID 按鍵事件已送出"))))) text = ""; busy = false }
                            }) { Text(if (busy) "傳送中…" else if (simulation) "模擬傳送" else "傳送到電腦", fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.action == Intent.ACTION_SEND) { imported = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""; shareSequence++ } }
    override fun onDestroy() { transport.dispose(); super.onDestroy() }
}

private fun techShape(cut: Float) = GenericShape { size, _ ->
    val c = minOf(cut, size.width / 4f, size.height / 4f)
    moveTo(c, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c); lineTo(size.width, size.height - c); lineTo(size.width - c, size.height); lineTo(c, size.height); lineTo(0f, size.height - c); lineTo(0f, c); close()
}

@Composable private fun BridgeMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply { moveTo(0f,h*.8f); cubicTo(w*.28f,h*.18f,w*.48f,h*.22f,w*.68f,h*.8f) }
        val q = Path().apply { moveTo(w*.30f,h*.8f); cubicTo(w*.58f,-h*.3f,w*.79f,-h*.25f,w*.98f,h*.8f) }
        drawPath(p, Color(0xFFDCE1DF), style = Stroke(width = h*.09f, cap = StrokeCap.Round))
        drawPath(q, Color(0xFFD3F34D), style = Stroke(width = h*.09f, cap = StrokeCap.Round))
    }
}

@Composable private fun TechIcon(kind: String, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width; val h = size.height; val stroke = size.minDimension * .075f
        fun line(x1:Float,y1:Float,x2:Float,y2:Float) = drawLine(color, Offset(w*x1,h*y1), Offset(w*x2,h*y2), stroke, cap = StrokeCap.Round)
        when(kind) {
            "phone" -> { drawRoundRect(color, Offset(w*.22f,h*.07f), Size(w*.56f,h*.84f), androidx.compose.ui.geometry.CornerRadius(w*.07f), style = Stroke(stroke)); line(.4f,.16f,.6f,.16f); drawCircle(color,w*.035f,Offset(w*.5f,h*.80f)) }
            "computer" -> { drawRoundRect(color, Offset(w*.10f,h*.15f), Size(w*.8f,h*.56f), androidx.compose.ui.geometry.CornerRadius(w*.04f), style = Stroke(stroke)); line(.02f,.86f,.98f,.86f) }
            "bluetooth" -> { line(.5f,.05f,.5f,.95f); line(.5f,.05f,.8f,.28f); line(.8f,.28f,.23f,.72f); line(.23f,.28f,.8f,.72f); line(.8f,.72f,.5f,.95f) }
            "mic" -> { drawRoundRect(color, Offset(w*.36f,h*.10f), Size(w*.28f,h*.48f), androidx.compose.ui.geometry.CornerRadius(w*.14f), style = Stroke(stroke)); val a=Path().apply { moveTo(w*.20f,h*.45f); cubicTo(w*.20f,h*.90f,w*.80f,h*.90f,w*.80f,h*.45f) }; drawPath(a,color,style=Stroke(stroke,cap=StrokeCap.Round)); line(.5f,.82f,.5f,.96f); line(.34f,.96f,.66f,.96f) }
        }
    }
}
