package com.myclivebot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.myclivebot.core.PiperSpeechEngine
import kotlinx.coroutines.launch

private val Night = Color(0xFF090B18)
private val Panel = Color(0xCC1B2036)
private val Violet = Color(0xFF9B86FF)
private val Cyan = Color(0xFF69DED2)
private val Muted = Color(0xFFB8BBD0)

class MainActivity : ComponentActivity() {
    private val readerStatus = mutableStateOf("Detenido")
    private var statusReceiverRegistered = false
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            readerStatus.value = intent?.getStringExtra(BotForegroundService.EXTRA_STATUS) ?: return
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            BotApp(
                externalStatus = readerStatus.value,
                voiceInitiallyInstalled = PiperSpeechEngine.isVoiceInstalled(this),
                onStart = { username, readName, blocked, ignored, maxQueue ->
                    val intent = Intent(this, BotForegroundService::class.java)
                        .setAction(BotForegroundService.ACTION_START)
                        .putExtra(BotForegroundService.EXTRA_USERNAME, username)
                        .putExtra(BotForegroundService.EXTRA_READ_USERNAME, readName)
                        .putStringArrayListExtra(BotForegroundService.EXTRA_BLOCKED_WORDS, ArrayList(blocked))
                        .putStringArrayListExtra(BotForegroundService.EXTRA_IGNORED_USERS, ArrayList(ignored))
                        .putExtra(BotForegroundService.EXTRA_MAX_QUEUE, maxQueue)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onStop = { startService(Intent(this, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_STOP)) },
                onTestVoice = {
                    val intent = Intent(this, BotForegroundService::class.java).setAction(BotForegroundService.ACTION_TEST_VOICE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                },
                onDownloadVoice = { callback ->
                    lifecycleScope.launch {
                        runCatching { PiperSpeechEngine.downloadVoice(this@MainActivity) }
                            .onSuccess { runOnUiThread { callback(null) } }
                            .onFailure { error -> runOnUiThread { callback(error.message ?: "Error descargando la voz") } }
                    }
                }
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!statusReceiverRegistered) {
            val filter = IntentFilter(BotForegroundService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else { @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter) }
            statusReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver)
            statusReceiverRegistered = false
        }
        super.onStop()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BotApp(
    externalStatus: String,
    voiceInitiallyInstalled: Boolean,
    onStart: (String, Boolean, List<String>, List<String>, Int) -> Unit,
    onStop: () -> Unit,
    onTestVoice: () -> Unit,
    onDownloadVoice: ((String?) -> Unit) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var readUsername by remember { mutableStateOf(true) }
    var maxQueue by remember { mutableStateOf("30") }
    var blockedWords by remember { mutableStateOf("") }
    var ignoredUsers by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Detenido") }
    var voiceInstalled by remember { mutableStateOf(voiceInitiallyInstalled) }
    var voiceStatus by remember { mutableStateOf(if (voiceInstalled) "Voz Piper es-MX instalada" else "Voz Piper es-MX pendiente de descarga") }
    var downloading by remember { mutableStateOf(false) }
    val connected = status.contains("Conectado", true) && !status.contains("no se pudo", true)
    val pulse by animateFloatAsState(if (connected) 1f else 0.72f, label = "statusPulse")
    LaunchedEffect(externalStatus) { status = externalStatus }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Violet, secondary = Cyan, background = Night, surface = Panel,
        onBackground = Color(0xFFF4F1FF), onSurface = Color(0xFFF4F1FF),
        surfaceVariant = Color(0xFF292D45), onSurfaceVariant = Muted
    )) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF17152D), Night, Color(0xFF101D2C))))) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 8.dp).size(190.dp).blur(75.dp).background(Violet.copy(alpha = .22f), RoundedCornerShape(100)))
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 100.dp, start = 0.dp).size(170.dp).blur(75.dp).background(Cyan.copy(alpha = .13f), RoundedCornerShape(100)))
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("TIKTOK LIVE READER", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium, letterSpacing = 1.2.sp)
                                Text("LECTOR INTELIGENTE · PIPER OFFLINE", style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = 1.sp)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0x99111629))
                    )
                }
            ) { padding ->
                Column(
                    Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    GlassCard {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Violet.copy(alpha = .18f)).border(1.dp, Violet.copy(alpha = .5f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.GraphicEq, null, tint = Violet)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("CENTRO DE CONTROL", style = MaterialTheme.typography.labelSmall, color = Cyan, letterSpacing = 1.4.sp)
                                    Text("Estado del lector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                                Box(Modifier.size(11.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.clip(RoundedCornerShape(50)).background(if (connected) Cyan else Violet))
                            }
                            Text(status, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            Text("Lee el chat público del LIVE y convierte los comentarios en voz en tu dispositivo.", color = Muted, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        val clean = username.trim().removePrefix("@")
                                        when {
                                            clean.isBlank() -> status = "Escribe el usuario de TikTok"
                                            !voiceInstalled -> status = "Descarga primero la voz Piper"
                                            else -> { onStart(clean, readUsername, splitList(blockedWords), splitList(ignoredUsers), maxQueue.toIntOrNull()?.coerceIn(5, 100) ?: 30); status = "Servicio iniciado · conectando…" }
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(54.dp),
                                    enabled = !downloading,
                                    shape = RoundedCornerShape(17.dp)
                                ) { Icon(Icons.Default.PlayArrow, null); Text(" INICIAR", fontWeight = FontWeight.Bold) }
                                OutlinedButton(onClick = { onStop(); status = "Detenido" }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(17.dp)) {
                                    Icon(Icons.Default.Stop, null); Text(" DETENER", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    SectionHeader("01", "Conexión TikTok LIVE", "Introduce el usuario del creador")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Usuario de TikTok") }, placeholder = { Text("@ejemplo_usuario") }, leadingIcon = { Icon(Icons.Default.AlternateEmail, null, tint = Violet) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
                            Text("El creador debe tener un LIVE activo. No se solicita contraseña ni cookies.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    SectionHeader("02", "Lectura y moderación", "Controla qué comentarios llegan a la voz")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingSwitch("Leer nombre del usuario", readUsername) { readUsername = it }
                            HorizontalDivider(color = Color.White.copy(alpha = .09f))
                            OutlinedTextField(value = blockedWords, onValueChange = { blockedWords = it }, label = { Text("Palabras bloqueadas · separadas por coma") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(value = ignoredUsers, onValueChange = { ignoredUsers = it }, label = { Text("Usuarios ignorados · separados por coma") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(value = maxQueue, onValueChange = { maxQueue = it.filter(Char::isDigit).take(3) }, label = { Text("Tamaño de cola (5–100)") }, leadingIcon = { Icon(Icons.Default.Queue, null, tint = Violet) }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
                        }
                    }

                    SectionHeader("03", "Voz neuronal Piper", "Español de México · procesamiento local")
                    GlassCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.RecordVoiceOver, null, tint = Cyan, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Piper TTS · es-MX", fontWeight = FontWeight.Bold)
                                    Text(voiceStatus, color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                                Surface(color = if (voiceInstalled) Cyan.copy(alpha = .16f) else Violet.copy(alpha = .16f), shape = RoundedCornerShape(50)) {
                                    Text(if (voiceInstalled) "LISTA" else "PENDIENTE", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = if (voiceInstalled) Cyan else Violet, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (!voiceInstalled) {
                                Button(enabled = !downloading, onClick = {
                                    downloading = true
                                    voiceStatus = "Descargando modelo de voz…"
                                    onDownloadVoice { error ->
                                        downloading = false
                                        if (error == null) { voiceInstalled = true; voiceStatus = "Voz Piper es-MX instalada y verificada" }
                                        else voiceStatus = "Error: $error"
                                    }
                                }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp)) {
                                    if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null)
                                    Text(if (downloading) " DESCARGANDO…" else " DESCARGAR VOZ")
                                }
                            }
                            OutlinedButton(onClick = onTestVoice, modifier = Modifier.fillMaxWidth().height(50.dp), enabled = voiceInstalled, shape = RoundedCornerShape(15.dp)) {
                                Icon(Icons.Default.VolumeUp, null); Text(" PROBAR VOZ")
                            }
                            Text("Una vez instalada, la síntesis se realiza sin enviar comentarios a servidores externos.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("TIKTOK LIVE READER  •  v0.6  •  SIN DISCORD", modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Muted.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().shadow(18.dp, RoundedCornerShape(24.dp)).border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .24f), Violet.copy(alpha = .22f), Color.White.copy(alpha = .04f))), RoundedCornerShape(24.dp)).animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

@Composable
private fun SectionHeader(number: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(number, color = Violet, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelLarge)
        Column {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun splitList(value: String): List<String> = value.split(',', '\\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
