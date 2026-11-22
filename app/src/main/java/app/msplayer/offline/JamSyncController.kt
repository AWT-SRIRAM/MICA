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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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
    private val jamScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessionState = MutableStateFlow(JamSessionState())
    val sessionState: StateFlow<JamSessionState> = _sessionState.asStateFlow()

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val activeClients = CopyOnWriteArrayList<Socket>()
    private val clientWriters = CopyOnWriteArrayList<PrintWriter>()

    fun getLocalIpAddress(): String {
        var fallbackIp: String? = null
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
                        if (fallbackIp == null && !host.startsWith("127.")) {
                            fallbackIp = host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return fallbackIp ?: "127.0.0.1"
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

    fun startHost(scope: CoroutineScope? = null, service: PlaybackService) {
        leaveJam()
        val ip = getLocalIpAddress()
        val randomSuffix = (1000..9999).random().toString()
        val roomCode = "JAM-$randomSuffix"
        val topic = "mica_jam_$randomSuffix"
        val ctlTopic = "${topic}_ctl"
        val remotePeers = java.util.concurrent.ConcurrentHashMap<String, Long>()

        _sessionState.value = JamSessionState(
            role = JamRole.HOST,
            roomCode = roomCode,
            hostAddress = ip,
            isConnected = true,
            listenerCount = 0,
            statusText = "Host listening worldwide ($roomCode)",
        )

        serverJob = jamScope.launch {
            try {
                // 1. Control channel listener for receiver PINGs & JOINs
                launch {
                    NtfyRelay.listen(ctlTopic, this) { line ->
                        runCatching {
                            val peerId = if (line.startsWith("{")) {
                                JSONObject(line).optString("peerId", "")
                            } else if (line.contains(":")) {
                                line.substringAfter(":").trim()
                            } else line.trim()
                            if (peerId.isNotBlank()) {
                                remotePeers[peerId] = System.currentTimeMillis()
                                val total = (activeClients.size + remotePeers.size).coerceAtLeast(0)
                                _sessionState.value = _sessionState.value.copy(
                                    listenerCount = total,
                                    statusText = if (total > 0) "$total ${if (total == 1) "peer" else "peers"} connected" else "Host listening worldwide ($roomCode)",
                                )
                            }
                        }
                    }
                }

                // 2. Local LAN ServerSocket (for Wi-Fi / Hotspot)
                val server = runCatching { ServerSocket(PORT).also { serverSocket = it } }.getOrNull()

                // 3. Sync broadcast loop (publishes to both HTTPS Ntfy and local TCP)
                launch {
                    while (isActive) {
                        val itm = service.state.value.item
                        val playing = service.state.value.playing
                        val pos = service.player.time.coerceAtLeast(0L)
                        val dur = service.state.value.duration

                        // Clean stale peers (no ping in 20s)
                        val now = System.currentTimeMillis()
                        val staleKeys = remotePeers.filter { now - it.value > 20000L }.keys
                        staleKeys.forEach { remotePeers.remove(it) }

                        val totalPeers = (activeClients.size + remotePeers.size).coerceAtLeast(0)
                        _sessionState.value = _sessionState.value.copy(
                            listenerCount = totalPeers,
                            statusText = if (totalPeers > 0) "$totalPeers ${if (totalPeers == 1) "peer" else "peers"} connected" else "Host listening worldwide ($roomCode)",
                        )

                        val uri = itm?.uri ?: ""
                        val title = itm?.title ?: ""
                        val artist = itm?.artist ?: ""
                        val thumb = itm?.thumbUri ?: ""

                        val payload = JSONObject().apply {
                            put("action", "SYNC")
                            put("uri", uri)
                            put("title", title)
                            put("artist", artist)
                            put("thumbUri", thumb)
                            put("pos", pos)
                            put("dur", dur)
                            put("playing", playing)
                            put("peerCount", totalPeers)
                            put("serverTime", System.currentTimeMillis())
                        }.toString()

                        broadcastToClients(payload)
                        NtfyRelay.publish(topic, payload)

                        delay(1200)
                    }
                }

                if (server != null) {
                    while (isActive) {
                        val client = server.accept()
                        activeClients.add(client)
                        val writer = PrintWriter(client.getOutputStream(), true)
                        clientWriters.add(writer)

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
                            }
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

    fun joinJam(scope: CoroutineScope? = null, hostInput: String, service: PlaybackService) {
        leaveJam()
        val cleanInput = hostInput.trim().uppercase()
        val isDirectIp = cleanInput.contains(".")
        val formattedCode = if (isDirectIp || cleanInput.startsWith("JAM-")) cleanInput else "JAM-$cleanInput"
        val cleanSuffix = cleanInput.removePrefix("JAM-").removePrefix("#").lowercase()
        val topic = "mica_jam_$cleanSuffix"
        val ctlTopic = "${topic}_ctl"
        val myPeerId = "listener_" + (100000..999999).random().toString()

        _sessionState.value = JamSessionState(
            role = JamRole.LISTENER,
            roomCode = formattedCode,
            hostAddress = cleanInput,
            isConnected = true,
            listenerCount = 1,
            statusText = "Connected to Jam Room ($formattedCode)",
        )

        clientJob = jamScope.launch {
            // 1. Heartbeat loop: sends PING to Host immediately, then every 5s
            launch {
                while (isActive) {
                    NtfyRelay.publish(ctlTopic, """{"action":"PING","peerId":"$myPeerId"}""")
                    delay(5000L)
                }
            }

            // 2. Global HTTPS Relay listener
            val relayJob = launch {
                NtfyRelay.listen(
                    topic = topic,
                    scope = this,
                    onConnected = {
                        _sessionState.value = _sessionState.value.copy(
                            isConnected = true,
                            statusText = "Connected to Jam Room ($cleanInput)",
                        )
                    }
                ) { line ->
                    handleHostSync(line, service, cleanInput)
                }
            }

            // 3. Fallback / local LAN TCP socket listener if on same Wi-Fi / Hotspot or direct IP
            launch {
                try {
                    val localIp = getLocalIpAddress()
                    val targetIp = if (isDirectIp) cleanInput else (roomCodeToIp(cleanInput, localIp) ?: cleanInput)
                    if (targetIp != "127.0.0.1" && targetIp != localIp) {
                        val socket = Socket(targetIp, PORT).also { clientSocket = it }
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        _sessionState.value = _sessionState.value.copy(
                            isConnected = true,
                            statusText = "Connected to Local Jam Host",
                        )
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            handleHostSync(line, service, cleanInput)
                        }
                    }
                } catch (_: Exception) {}
            }

            try {
                relayJob.join()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _sessionState.value = _sessionState.value.copy(
                        isConnected = false,
                        statusText = "Connection lost: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun handleHostSync(line: String, service: PlaybackService, roomLabel: String = "") = withContext(Dispatchers.Main) {
        runCatching {
            val json = JSONObject(line)
            when (json.optString("action")) {
                "PEER_COUNT" -> {
                    val count = json.optInt("count", 1)
                    _sessionState.value = _sessionState.value.copy(listenerCount = count.coerceAtLeast(1))
                }
                "SYNC" -> {
                    val uri = json.optString("uri", "")
                    val title = json.optString("title", "")
                    val artist = json.optString("artist", "")
                    val thumbUri = json.optString("thumbUri", "")
                    val targetPos = json.optLong("pos", 0L)
                    val isPlaying = json.optBoolean("playing", false)
                    val hostPeerCount = json.optInt("peerCount", 1)
                    val serverTime = json.optLong("serverTime", System.currentTimeMillis())
                    val latency = (System.currentTimeMillis() - serverTime).coerceIn(0L, 2000L)
                    val compensatedPos = targetPos + (if (isPlaying) latency else 0L)

                    // On listener: total participants = host (1) + peers
                    val displayPeers = (hostPeerCount + 1).coerceAtLeast(1)

                    if (uri.isNotBlank() && title.isNotBlank()) {
                        val curItem = service.state.value.item
                        val isSameSong = curItem != null && (curItem.title.equals(title, ignoreCase = true) || curItem.uri == uri)
                        if (!isSameSong) {
                            val streamUri = if (PlaybackRules.isStreamUrl(uri)) {
                                uri
                            } else {
                                "resolve://audio/${title}::${artist}"
                            }
                            val jamItem = MediaItem(
                                uri = streamUri,
                                title = title,
                                artist = artist,
                                thumbUri = thumbUri.takeIf { it.isNotBlank() },
                                folder = "MICA Jam Room",
                                audio = true,
                            )
                            service.open(listOf(jamItem), jamItem, autoPlay = true)
                        }

                        // Sync position if drifted > 900ms
                        val currentPos = service.player.time
                        if (abs(currentPos - compensatedPos) > 900L) {
                            service.player.time = compensatedPos
                        }

                        // Sync play / pause
                        if (isPlaying && !service.state.value.playing) {
                            service.play()
                        } else if (!isPlaying && service.state.value.playing) {
                            service.pause()
                        }

                        _sessionState.value = _sessionState.value.copy(
                            isConnected = true,
                            listenerCount = displayPeers,
                            currentTrackTitle = "$title • $artist",
                            statusText = "Synced • $title",
                        )
                    } else {
                        // Host is alive but not currently playing
                        _sessionState.value = _sessionState.value.copy(
                            isConnected = true,
                            listenerCount = displayPeers,
                            statusText = "Connected to Jam Room (Waiting for host to play)",
                        )
                    }
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
                        folder = "MICA Jam Queue",
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
                    text = "MICA Jam — Listen Together",
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
                if (sessionState.role != JamRole.NONE) {
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
                                            .background(if (sessionState.isConnected) Color(0xFF10B981) else Color(0xFFF59E0B))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (sessionState.role == JamRole.HOST) "HOSTING JAM" else "LISTENING IN JAM",
                                        color = if (sessionState.isConnected) Color(0xFF10B981) else Color(0xFFF59E0B),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Group,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    val count = sessionState.listenerCount
                                    val peerLabel = if (sessionState.role == JamRole.HOST) {
                                        "$count ${if (count == 1) "peer" else "peers"}"
                                    } else {
                                        "${count.coerceAtLeast(1)} ${if (count <= 1) "peer (Host)" else "peers"}"
                                    }
                                    Text(
                                        text = peerLabel,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                    )
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
                            text = "Enter your friend's room number (e.g. 3213 or 8957) or direct IP to sync playback.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        OutlinedTextField(
                            value = joinCodeInput,
                            onValueChange = { input ->
                                val clean = input.uppercase().removePrefix("JAM-").removePrefix("JAM -").removePrefix("JAM").removePrefix("-").trim()
                                if (clean.length <= 15) {
                                    joinCodeInput = clean
                                }
                            },
                            prefix = {
                                if (!joinCodeInput.contains(".")) {
                                    Text(
                                        text = "JAM - ",
                                        color = Color(0xFF818CF8),
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.sp,
                                    )
                                }
                            },
                            placeholder = {
                                Text(
                                    text = if (joinCodeInput.contains(".")) "192.168.43.1" else "3213",
                                    color = Color.White.copy(alpha = 0.35f),
                                    fontSize = 17.sp,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 2.sp,
                                )
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                            ),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii
                            ),
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
                                        val targetCode = if (joinCodeInput.contains(".") || joinCodeInput.startsWith("JAM-")) {
                                            joinCodeInput
                                        } else {
                                            "JAM-$joinCodeInput"
                                        }
                                        JamSyncController.joinJam(scope, targetCode, service)
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
 * Zero-dependency, ultra-fast HTTPS Pub/Sub Relay based on the public SSE standard.
 * Connects instantly across mobile cellular (Jio, Airtel 4G/5G) and Wi-Fi networks worldwide
 * without requiring open inbound ports or port forwarding.
 */
object NtfyRelay {
    private const val BASE_URL = "https://ntfy.sh"

    fun publish(topic: String, message: String): Boolean {
        return try {
            val url = URL("$BASE_URL/$topic")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "MicaPlayer/0.8.9")
            }
            conn.outputStream.use { it.write(message.toByteArray(Charsets.UTF_8)) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    suspend fun listen(
        topic: String,
        scope: CoroutineScope,
        onConnected: () -> Unit = {},
        onMessage: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var retryDelay = 1000L
        while (scope.isActive) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$BASE_URL/$topic/raw")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 45000 // Long-poll timeout
                    setRequestProperty("User-Agent", "MicaPlayer/0.8.9")
                }
                if (conn.responseCode in 200..299) {
                    onConnected()
                    retryDelay = 1000L
                    val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    while (scope.isActive) {
                        val line = reader.readLine() ?: break
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) {
                            onMessage(trimmed)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) break
            } finally {
                runCatching { conn?.disconnect() }
            }
            if (scope.isActive) {
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(6000L)
            }
        }
    }
}
