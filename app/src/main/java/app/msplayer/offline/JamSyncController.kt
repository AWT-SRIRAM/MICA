package app.msplayer.offline

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

enum class JamRole {
    NONE,
    HOST,
    LISTENER,
}

data class JamSessionState(
    val role: JamRole = JamRole.NONE,
    val roomCode: String = "",
    val hostAddress: String = "",
    val isConnected: Boolean = false,
    val listenerCount: Int = 0,
    val statusText: String = "Idle",
    val currentTrackTitle: String = "",
)

object JamSyncController {
    private const val PORT = 8989

    private val _sessionState = MutableStateFlow(JamSessionState())
    val sessionState: StateFlow<JamSessionState> = _sessionState.asStateFlow()

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val activeClients = CopyOnWriteArrayList<Socket>()
    private val clientWriters = CopyOnWriteArrayList<PrintWriter>()

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: ""
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun ipToRoomCode(ip: String): String {
        return try {
            val parts = ip.split('.').map { it.toInt() }
            if (parts.size == 4) {
                val p3 = parts[2].toString(16).uppercase().padStart(2, '0')
                val p4 = parts[3].toString(16).uppercase().padStart(2, '0')
                "JAM-$p3$p4"
            } else "JAM-0000"
        } catch (_: Exception) {
            "JAM-0000"
        }
    }

    fun roomCodeToIp(code: String, localSubnet: String = "192.168."): String? {
        val clean = code.trim().uppercase()
        if (clean.contains(".")) return clean // Already direct IP
        val hex = clean.removePrefix("JAM-").removePrefix("#")
        if (hex.length == 4) {
            return try {
                val p3 = hex.substring(0, 2).toInt(16)
                val p4 = hex.substring(2, 4).toInt(16)
                // Use local subnet prefix if available
                val base = localSubnet.substringBeforeLast('.', "192.168").substringBeforeLast('.', "192.168")
                "$base.$p3.$p4"
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    fun startHost(scope: CoroutineScope, service: PlaybackService) {
        leaveJam()
        val ip = getLocalIpAddress()
        val randomSuffix = (1000..9999).random().toString()
        val roomCode = "JAM-$randomSuffix"
        val topic = "msplayer/jam/$randomSuffix"

        _sessionState.value = JamSessionState(
            role = JamRole.HOST,
            roomCode = roomCode,
            hostAddress = ip,
            isConnected = true,
            listenerCount = 0,
            statusText = "Host listening worldwide ($roomCode)",
        )

        serverJob = scope.launch(Dispatchers.IO) {
            var mqttClient: MinimalMqttClient? = null
            try {
                val mqtt = MinimalMqttClient(clientId = "msplayer_host_$randomSuffix")
                if (mqtt.connect()) {
                    mqttClient = mqtt
                }

                val server = ServerSocket(PORT).also { serverSocket = it }
                // Broadcast sync loop
                val broadcastJob = launch {
                    while (isActive) {
                        val itm = service.state.value.item
                        val playing = service.state.value.playing
                        val pos = service.player.time.coerceAtLeast(0L)
                        val dur = service.state.value.duration

                        if (itm != null) {
                            val uri = itm.uri
                            val title = itm.title
                            val artist = itm.artist
                            val thumb = itm.thumbUri ?: ""

                            val payload = JSONObject().apply {
                                put("action", "SYNC")
                                put("uri", uri)
                                put("title", title)
                                put("artist", artist)
                                put("thumbUri", thumb)
                                put("pos", pos)
                                put("dur", dur)
                                put("playing", playing)
                                put("serverTime", System.currentTimeMillis())
                            }.toString()

                            broadcastToClients(payload)
                            mqttClient?.publish(topic, payload)
                        }
                        delay(1200)
                    }
                }

                while (isActive) {
                    val client = server.accept()
                    activeClients.add(client)
                    val writer = PrintWriter(client.getOutputStream(), true)
                    clientWriters.add(writer)

                    _sessionState.value = _sessionState.value.copy(
                        listenerCount = activeClients.size,
                        statusText = "${activeClients.size} listeners connected",
                    )

                    // Client listener worker
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                handleClientMessage(line, service)
                            }
                        } catch (_: Exception) {
                        } finally {
                            activeClients.remove(client)
                            clientWriters.remove(writer)
                            runCatching { client.close() }
                            _sessionState.value = _sessionState.value.copy(
                                listenerCount = activeClients.size,
                                statusText = "${activeClients.size} listeners connected",
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _sessionState.value = _sessionState.value.copy(
                        statusText = "Host error: ${e.message}",
                        isConnected = false,
                    )
                }
            } finally {
                mqttClient?.close()
            }
        }
    }

    private fun broadcastToClients(jsonStr: String) {
        val stale = mutableListOf<PrintWriter>()
        for (w in clientWriters) {
            try {
                w.println(jsonStr)
            } catch (_: Exception) {
                stale.add(w)
            }
        }
        clientWriters.removeAll(stale)
    }

    fun joinJam(scope: CoroutineScope, hostInput: String, service: PlaybackService) {
        leaveJam()
        val cleanInput = hostInput.trim().uppercase()
        val isDirectIp = cleanInput.contains(".")
        val cleanSuffix = cleanInput.removePrefix("JAM-").removePrefix("#").lowercase()
        val topic = "msplayer/jam/$cleanSuffix"

        _sessionState.value = JamSessionState(
            role = JamRole.LISTENER,
            roomCode = cleanInput,
            hostAddress = cleanInput,
            isConnected = false,
            statusText = "Connecting to Jam Room $cleanInput...",
        )

        clientJob = scope.launch(Dispatchers.IO) {
            // Path A: Global Internet Relay via MQTT (Covai <-> Madurai worldwide)
            if (!isDirectIp) {
                val mqtt = MinimalMqttClient(clientId = "msplayer_listener_${System.currentTimeMillis()}")
                if (mqtt.connect() && mqtt.subscribe(topic)) {
                    _sessionState.value = _sessionState.value.copy(
                        isConnected = true,
                        statusText = "Connected to worldwide Jam Room ($cleanInput)",
                    )
                    try {
                        while (isActive) {
                            val pair = mqtt.readMessage()
                            if (pair != null) {
                                handleHostSync(pair.second, service)
                            } else {
                                delay(200)
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        mqtt.close()
                    }
                    return@launch
                }
                mqtt.close()
            }

            // Path B: Fallback to Local LAN / Hotspot Socket
            try {
                val localIp = getLocalIpAddress()
                val targetIp = if (isDirectIp) cleanInput else (roomCodeToIp(cleanInput, localIp) ?: cleanInput)
                val socket = Socket(targetIp, PORT).also { clientSocket = it }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                _sessionState.value = _sessionState.value.copy(
                    isConnected = true,
                    statusText = "Connected to Local Jam Host",
                )

                while (isActive) {
                    val line = reader.readLine() ?: break
                    handleHostSync(line, service)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _sessionState.value = _sessionState.value.copy(
                        isConnected = false,
                        statusText = "Connection failed: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun handleHostSync(line: String, service: PlaybackService) = withContext(Dispatchers.Main) {
        runCatching {
            val json = JSONObject(line)
            when (json.optString("action")) {
                "SYNC" -> {
                    val uri = json.getString("uri")
                    val title = json.getString("title")
                    val artist = json.optString("artist", "")
                    val thumbUri = json.optString("thumbUri", "")
                    val targetPos = json.getLong("pos")
                    val isPlaying = json.getBoolean("playing")
                    val serverTime = json.optLong("serverTime", System.currentTimeMillis())
                    val latency = (System.currentTimeMillis() - serverTime).coerceAtLeast(0L)
                    val compensatedPos = targetPos + (if (isPlaying) latency else 0L)

                    val curItem = service.state.value.item
                    if (curItem == null || curItem.uri != uri) {
                        // Play matching track
                        val jamItem = MediaItem(
                            uri = uri,
                            title = title,
                            artist = artist,
                            thumbUri = thumbUri.takeIf { it.isNotBlank() },
                            folder = "Mica Jam Room",
                            audio = true,
                        )
                        service.open(listOf(jamItem), jamItem, autoPlay = true)
                    }

                    // Sync position if drifted > 700ms
                    val currentPos = service.player.time
                    if (abs(currentPos - compensatedPos) > 700L) {
                        service.player.time = compensatedPos
                    }

                    // Sync play / pause
                    if (isPlaying && !service.state.value.playing) {
                        service.play()
                    } else if (!isPlaying && service.state.value.playing) {
                        service.pause()
                    }

                    _sessionState.value = _sessionState.value.copy(
                        currentTrackTitle = "$title • $artist",
                        statusText = "Synced • Drift: ${abs(currentPos - compensatedPos)}ms",
                    )
                }
            }
        }
    }

    private fun handleClientMessage(line: String, service: PlaybackService) {
        runCatching {
            val json = JSONObject(line)
            when (json.optString("action")) {
                "QUEUE_ADD" -> {
                    val uri = json.getString("uri")
                    val title = json.getString("title")
                    val artist = json.optString("artist", "")
                    val thumbUri = json.optString("thumbUri", "")
                    val item = MediaItem(
                        uri = uri,
                        title = title,
                        artist = artist,
                        thumbUri = thumbUri.takeIf { it.isNotBlank() },
                        folder = "Mica Jam Queue",
                        audio = true,
                    )
                    service.appendToQueue(listOf(item))
                }
            }
        }
    }

    fun leaveJam() {
        serverJob?.cancel()
        clientJob?.cancel()
        serverJob = null
        clientJob = null

        runCatching { serverSocket?.close() }
        serverSocket = null

        runCatching { clientSocket?.close() }
        clientSocket = null

        for (c in activeClients) {
            runCatching { c.close() }
        }
        activeClients.clear()
        clientWriters.clear()

        _sessionState.value = JamSessionState(role = JamRole.NONE, statusText = "Disconnected")
    }
}

@Composable
fun JamSyncDialog(
    service: PlaybackService?,
    onDismiss: () -> Unit,
) {
    val sessionState by JamSyncController.sessionState.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(if (sessionState.role == JamRole.LISTENER) 1 else 0) }
    var joinCodeInput by remember { mutableStateOf("") }
    var copiedCode by remember { mutableStateOf(false) }

    LiquidGlassAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Podcasts,
                    contentDescription = null,
                    tint = Color(0xFF6366F1),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Mica Jam — Listen Together",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                if (sessionState.role != JamRole.NONE && sessionState.isConnected) {
                    // Active Jam Room Status Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF131722))
                            .border(1.dp, Color(0xFF6366F1).copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (sessionState.role == JamRole.HOST) "HOSTING JAM" else "LISTENING IN JAM",
                                        color = Color(0xFF10B981),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    )
                                }

                                if (sessionState.role == JamRole.HOST) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Group,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${sessionState.listenerCount} peers",
                                            color = Color.White.copy(alpha = 0.7f),
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "Room Code",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = sessionState.roomCode,
                                    color = Color.White,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                )

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.12f))
                                        .clickable {
                                            clipboard.setText(AnnotatedString(sessionState.roomCode))
                                            copiedCode = true
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (copiedCode) Icons.Rounded.CheckCircle else Icons.Rounded.ContentCopy,
                                            contentDescription = null,
                                            tint = if (copiedCode) Color(0xFF10B981) else Color.White,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (copiedCode) "Copied" else "Copy",
                                            color = Color.White,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Direct IP: ${sessionState.hostAddress}",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = sessionState.statusText,
                                color = Color(0xFF818CF8),
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x22EF4444))
                            .clickable {
                                JamSyncController.leaveJam()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (sessionState.role == JamRole.HOST) "End Jam Room" else "Leave Jam Room",
                            color = Color(0xFFEF4444),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    // Start or Join Tabs
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = Color(0xFF6366F1),
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = Color(0xFF6366F1),
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Host a Jam", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Join a Jam", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedTab == 0) {
                        // Host Tab
                        Text(
                            text = "Start a shared listening session. Anyone on the same Wi-Fi or your mobile Hotspot can join and listen in real-time.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val localIp = remember { JamSyncController.getLocalIpAddress() }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Local IP: $localIp",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))
                                    )
                                )
                                .clickable {
                                    if (service != null) {
                                        JamSyncController.startHost(scope, service)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Start Jam Room",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        // Join Tab
                        Text(
                            text = "Enter your friend's 6-character room code (e.g. JAM-2B01) or direct IP to sync playback instantly.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = joinCodeInput,
                            onValueChange = { joinCodeInput = it.uppercase() },
                            placeholder = { Text("e.g. JAM-2B01 or 192.168.43.1", color = Color.White.copy(alpha = 0.4f), fontSize = 13.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF6366F1),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(18.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (joinCodeInput.isNotBlank()) {
                                        Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)))
                                    } else {
                                        Brush.horizontalGradient(listOf(Color(0xFF374151), Color(0xFF374151)))
                                    }
                                )
                                .clickable(enabled = joinCodeInput.isNotBlank()) {
                                    if (service != null && joinCodeInput.isNotBlank()) {
                                        JamSyncController.joinJam(scope, joinCodeInput, service)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Join Jam",
                                color = if (joinCodeInput.isNotBlank()) Color.White else Color.White.copy(alpha = 0.5f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Text(
                text = "Close",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onDismiss() }
                    .padding(8.dp)
            )
        },
        dismissButton = {}
    )
}

/**
 * Lightweight, zero-dependency pure Kotlin MQTT 3.1.1 socket client.
 * Connects to public zero-auth brokers (e.g. broker.hivemq.com:1883)
 * so friends can listen together anywhere in the world (Covai <-> Madurai on 4G/5G/Wi-Fi).
 */
class MinimalMqttClient(
    val host: String = "broker.hivemq.com",
    val port: Int = 1883,
    val clientId: String = "msplayer_" + System.currentTimeMillis()
) {
    private var socket: Socket? = null
    private var outStream: java.io.OutputStream? = null
    private var inStream: java.io.InputStream? = null
    private var connected = false

    fun connect(): Boolean {
        return try {
            val s = Socket(host, port).apply {
                soTimeout = 12000
                tcpNoDelay = true
            }
            socket = s
            val out = s.getOutputStream()
            val input = s.getInputStream()
            outStream = out
            inStream = input

            val clientIdBytes = clientId.toByteArray(Charsets.UTF_8)
            val protocolName = "MQTT".toByteArray(Charsets.UTF_8)
            val varHeaderAndPayload = java.io.ByteArrayOutputStream()
            // Protocol Name length (2 bytes) + "MQTT"
            varHeaderAndPayload.write(0)
            varHeaderAndPayload.write(4)
            varHeaderAndPayload.write(protocolName)
            // Protocol Level (4 = 3.1.1)
            varHeaderAndPayload.write(4)
            // Connect Flags (0x02 = Clean Session)
            varHeaderAndPayload.write(2)
            // Keep Alive (60 seconds)
            varHeaderAndPayload.write(0)
            varHeaderAndPayload.write(60)
            // Client ID
            varHeaderAndPayload.write(clientIdBytes.size shr 8)
            varHeaderAndPayload.write(clientIdBytes.size and 0xFF)
            varHeaderAndPayload.write(clientIdBytes)

            val packetBytes = varHeaderAndPayload.toByteArray()
            out.write(0x10) // CONNECT
            writeVariableLength(out, packetBytes.size)
            out.write(packetBytes)
            out.flush()

            // Read CONNACK
            val packetType = input.read()
            if (packetType == 0x20) {
                val remLen = readVariableLength(input)
                input.read() // ackFlags
                val returnCode = input.read()
                connected = (returnCode == 0)
                connected
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun subscribe(topic: String): Boolean {
        if (!connected) return false
        return try {
            val out = outStream ?: return false
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val body = java.io.ByteArrayOutputStream()
            // Packet ID = 1
            body.write(0)
            body.write(1)
            // Topic Length + Topic
            body.write(topicBytes.size shr 8)
            body.write(topicBytes.size and 0xFF)
            body.write(topicBytes)
            // Requested QoS = 0
            body.write(0)

            val packet = body.toByteArray()
            out.write(0x82) // SUBSCRIBE
            writeVariableLength(out, packet.size)
            out.write(packet)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun publish(topic: String, message: String): Boolean {
        if (!connected) return false
        return try {
            val out = outStream ?: return false
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val payloadBytes = message.toByteArray(Charsets.UTF_8)
            val body = java.io.ByteArrayOutputStream()
            // Topic Length + Topic
            body.write(topicBytes.size shr 8)
            body.write(topicBytes.size and 0xFF)
            body.write(topicBytes)
            // Payload
            body.write(payloadBytes)

            val packet = body.toByteArray()
            out.write(0x30) // PUBLISH QoS 0
            writeVariableLength(out, packet.size)
            out.write(packet)
            out.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readMessage(): Pair<String, String>? {
        val input = inStream ?: return null
        return try {
            val header = input.read()
            if (header == -1) return null
            val packetType = header shr 4
            val remLen = readVariableLength(input)
            val payload = ByteArray(remLen)
            var read = 0
            while (read < remLen) {
                val r = input.read(payload, read, remLen - read)
                if (r == -1) break
                read += r
            }
            if (packetType == 3 && remLen >= 2) { // PUBLISH
                val topicLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                val topic = String(payload, 2, topicLen, Charsets.UTF_8)
                val msg = String(payload, 2 + topicLen, remLen - (2 + topicLen), Charsets.UTF_8)
                Pair(topic, msg)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeVariableLength(out: java.io.OutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readVariableLength(input: java.io.InputStream): Int {
        var multiplier = 1
        var value = 0
        var digit: Int
        do {
            digit = input.read()
            value += (digit and 127) * multiplier
            multiplier *= 128
        } while ((digit and 128) != 0)
        return value
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
    }
}
