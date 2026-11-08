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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.myclivebot.core.PiperSpeechEngine
import kotlinx.coroutines.launch

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
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(statusReceiver, filter)
            }
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
    LaunchedEffect(externalStatus) { status = externalStatus }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF9C8CFF),
        secondary = androidx.compose.ui.graphics.Color(0xFF70D6CF),
        background = androidx.compose.ui.graphics.Color(0xFF0D1020),
        surface = androidx.compose.ui.graphics.Color(0xFF171B2E)
    )) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("TIKTOK LIVE READER", style = MaterialTheme.typography.titleLarge) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Card {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RadioButtonChecked, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Text("Estado del lector", style = MaterialTheme.typography.titleMedium)
                        }
                        Text(status, style = MaterialTheme.typography.headlineSmall)
                        Text("Conecta al chat público de un LIVE usando el usuario del creador. No pide contraseña ni cookies.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = {
                                val clean = username.trim().removePrefix("@")
                                when {
                                    clean.isBlank() -> status = "Escribe el usuario de TikTok"
                                    !voiceInstalled -> status = "Descarga primero la voz Piper"
                                    else -> { onStart(clean, readUsername, splitList(blockedWords), splitList(ignoredUsers), maxQueue.toIntOrNull()?.coerceIn(5, 100) ?: 30); status = "Servicio iniciado · conectando…" }
                                }
                            }, modifier = Modifier.weight(1f), enabled = !downloading) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null); Text(" Iniciar")
                            }
                            OutlinedButton(onClick = { onStop(); status = "Detenido" }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.Stop, contentDescription = null); Text(" Detener")
                            }
                        }
                    }
                }

                SectionTitle("TikTok LIVE")
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Usuario de TikTok (sin @)") }, placeholder = { Text("ejemplo_usuario") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("El creador debe tener un LIVE activo. La conexión comunitaria usa el servicio Webcast de TikTok y puede cambiar si TikTok actualiza su protocolo.", style = MaterialTheme.typography.bodySmall)

                SectionTitle("Lectura y moderación")
                SettingSwitch("Leer nombre del usuario", readUsername) { readUsername = it }
                OutlinedTextField(value = blockedWords, onValueChange = { blockedWords = it }, label = { Text("Palabras bloqueadas (separadas por coma)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(value = ignoredUsers, onValueChange = { ignoredUsers = it }, label = { Text("Usuarios ignorados (separados por coma)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(value = maxQueue, onValueChange = { maxQueue = it.filter(Char::isDigit).take(3) }, label = { Text("Máximo de mensajes en cola (5–100)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                SectionTitle("Voz neuronal local")
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Piper TTS · Español de México", style = MaterialTheme.typography.titleMedium)
                        Text(voiceStatus, style = MaterialTheme.typography.bodySmall)
                        if (!voiceInstalled) {
                            Button(enabled = !downloading, onClick = {
                                downloading = true
                                voiceStatus = "Descargando modelo de voz (aprox. 63 MB)…"
                                onDownloadVoice { error ->
                                    downloading = false
                                    if (error == null) {
                                        voiceInstalled = true
                                        voiceStatus = "Voz Piper es-MX instalada y verificada"
                                    } else voiceStatus = "Error: $error"
                                }
                            }, modifier = Modifier.fillMaxWidth()) {
                                if (downloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Default.Download, contentDescription = null)
                                Text(if (downloading) " Descargando…" else " Descargar voz Piper es-MX")
                            }
                        }
                        OutlinedButton(onClick = onTestVoice, modifier = Modifier.fillMaxWidth(), enabled = voiceInstalled) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null); Text(" Probar voz")
                        }
                        Text("La voz se descarga una vez y luego sintetiza en el teléfono sin enviar comentarios a servidores externos.", style = MaterialTheme.typography.bodySmall)
                    }
                }

                Text("Versión 0.5.0 · TikTok LIVE + Piper local · Sin Discord", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun splitList(value: String): List<String> = value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
