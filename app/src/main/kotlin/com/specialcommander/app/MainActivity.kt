package com.specialcommander.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CommanderApp()
        }
    }
}

private const val ENGINE_HOST = "127.0.0.1"
private const val ENGINE_PORT = 8765

sealed class EngineEvent {
    data class Hello(val version: String, val message: String) : EngineEvent()
    data class State(
        val mode: String,
        val dryRun: Boolean,
        val allowlistCount: Int,
        val error: String,
    ) : EngineEvent()

    data class Log(val message: String, val level: String) : EngineEvent()
    data class Ap(
        val bssid: String,
        val ssid: String,
        val channel: Int,
        val signal: Int,
        val security: String,
    ) : EngineEvent()

    data class Client(val mac: String, val bssid: String, val signal: Int) : EngineEvent()
    data class Bt(val mac: String, val name: String, val rssi: Int) : EngineEvent()
    data class FluxionLine(val line: String) : EngineEvent()
    data class FluxionCred(val username: String, val passwordLen: Int) : EngineEvent()
    data class Result(
        val cmd: String,
        val ok: Boolean,
        val message: String,
        val code: String,
    ) : EngineEvent()

    data class ErrorEvent(val code: String, val message: String) : EngineEvent()
    data class Other(val text: String) : EngineEvent()
}

class EngineClient(
    private val onStatus: (String) -> Unit,
    private val onMessage: (EngineEvent) -> Unit,
    private val onRaw: (String) -> Unit = {},
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val sendLock = Any()
    private val pendingSends = java.util.ArrayDeque<String>()
    @Volatile
    private var running = false

    suspend fun connect() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        running = true
        while (running) {
            var current: Socket? = null
            try {
                onStatus("Connecting...")
                val s = Socket()
                s.connect(InetSocketAddress(ENGINE_HOST, ENGINE_PORT), 3000)
                current = s
                socket = s
                writer = BufferedWriter(
                    OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8)
                )
                val connectedWriter = writer ?: throw IllegalStateException("writer unavailable")
                synchronized(sendLock) {
                    while (!pendingSends.isEmpty()) {
                        connectedWriter.write(pendingSends.removeFirst())
                        connectedWriter.write("\n")
                    }
                    connectedWriter.flush()
                }
                val reader = BufferedReader(
                    InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)
                )
                onStatus("Connected")
                while (running) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    onRaw(line)
                    onMessage(parseEngineLine(line))
                }
            } catch (e: Exception) {
                if (running) {
                    onStatus("Disconnected: ${e.message ?: "error"}")
                    onMessage(
                        EngineEvent.Log(
                            "Connection error: ${e.message ?: "error"}",
                            "error",
                        )
                    )
                }
            } finally {
                try {
                    current?.close()
                } catch (_: Exception) {
                }
                socket = null
                writer = null
            }
            if (running) {
                onStatus("Reconnecting...")
                delay(1500)
            }
        }
        onStatus("Disconnected")
    }

    fun send(cmd: String, extras: Map<String, Any> = emptyMap()) {
        val obj = JSONObject().put("cmd", cmd)
        extras.forEach { (key, value) -> obj.put(key, value) }
        val line = obj.toString()
        synchronized(sendLock) {
            val w = writer
            if (w == null) {
                if (pendingSends.size >= 20) pendingSends.removeFirst()
                pendingSends.addLast(line)
                onStatus("Queued: $cmd")
                return@synchronized
            }
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (e: Exception) {
                writer = null
                if (pendingSends.size >= 20) pendingSends.removeFirst()
                pendingSends.addLast(line)
                onMessage(
                    EngineEvent.Log(
                        "Send queued after reconnect: ${e.message ?: "error"}",
                        "warn",
                    )
                )
            }
        }
    }

    fun disconnect() {
        running = false
        synchronized(sendLock) { pendingSends.clear() }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        writer = null
        onStatus("Disconnected")
    }

}

fun parseEngineLine(line: String): EngineEvent {
    return try {
        val obj = JSONObject(line)
        when (obj.optString("type", "")) {
            "hello" -> EngineEvent.Hello(
                version = obj.optString("version", ""),
                message = obj.optString("message", ""),
            )

            "state" -> {
                val list = obj.optJSONArray("allowlist")
                EngineEvent.State(
                    mode = obj.optString("mode", "passive"),
                    dryRun = obj.optBoolean("dry_run", true),
                    allowlistCount = list?.length() ?: 0,
                    error = obj.optString("error", ""),
                )
            }

            "event" -> parseEvent(
                obj.optString("event", ""),
                obj.optJSONObject("data") ?: JSONObject(),
            )

            "result" -> EngineEvent.Result(
                cmd = obj.optString("cmd", ""),
                ok = obj.optBoolean("ok", false),
                message = obj.optString("message", ""),
                code = obj.optString("code", ""),
            )

            "error" -> EngineEvent.ErrorEvent(
                code = obj.optString("code", ""),
                message = obj.optString("message", ""),
            )

            else -> EngineEvent.Other(line)
        }
    } catch (e: Exception) {
        EngineEvent.Log("Bad engine message: ${e.message ?: "error"}", "error")
    }
}

private fun parseEvent(name: String, data: JSONObject): EngineEvent {
    return when (name) {
        "log" -> EngineEvent.Log(
            message = data.optString("message", ""),
            level = data.optString("level", "info"),
        )

        "wifi.ap" -> EngineEvent.Ap(
            bssid = data.optString("bssid", ""),
            ssid = data.optString("ssid", "(hidden)"),
            channel = data.optInt("channel", 0),
            signal = data.optInt("signal", -999),
            security = data.optString("security", ""),
        )

        "wifi.client" -> EngineEvent.Client(
            mac = data.optString("mac", ""),
            bssid = data.optString("bssid", ""),
            signal = data.optInt("signal", -999),
        )

        "bt.device" -> EngineEvent.Bt(
            mac = data.optString("mac", ""),
            name = data.optString("name", "(unknown)"),
            rssi = data.optInt("rssi", -999),
        )

        "fluxion.log" -> EngineEvent.FluxionLine(data.optString("line", ""))

        "fluxion.cred" -> EngineEvent.FluxionCred(
            username = data.optString("username", ""),
            passwordLen = data.optString("password_len", "0").toIntOrNull() ?: 0,
        )

        else -> EngineEvent.Other("$name $data")
    }
}

data class ApInfo(
    val bssid: String,
    val ssid: String,
    val channel: Int,
    val signal: Int,
    val security: String,
)

data class ClientInfo(
    val mac: String,
    val bssid: String,
    val signal: Int,
)

data class BtInfo(
    val mac: String,
    val name: String,
    val rssi: Int,
)

data class LogInfo(val message: String, val level: String)

data class UiState(
    val status: String = "Disconnected",
    val version: String = "",
    val mode: String = "passive",
    val dryRun: Boolean = true,
    val allowlistCount: Int = 0,
    val stateError: String = "",
    val selectedBssid: String = "",
    val aps: List<ApInfo> = emptyList(),
    val clients: List<ClientInfo> = emptyList(),
    val btDevices: List<BtInfo> = emptyList(),
    val logs: List<LogInfo> = emptyList(),
    val fluxionLines: List<String> = emptyList(),
    val creds: List<String> = emptyList(),
    val lastResult: String = "",
    val apEvents: Int = 0,
    val lastEvent: String = "",
    val lastRaw: String = "",
    val parseTest: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommanderApp() {
    var ui by remember { mutableStateOf(UiState()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Engine callbacks arrive on the socket thread. Compose state must be
    // updated on the main thread so the Wi-Fi list actually recomposes.
    fun uiPost(block: () -> Unit) {
        mainHandler.post { block() }
    }

    fun handle(event: EngineEvent) {
        ui = ui.copy(
            lastEvent = when (event) {
                is EngineEvent.Hello -> "hello"
                is EngineEvent.State -> "state"
                is EngineEvent.Log -> "log"
                is EngineEvent.Ap -> "wifi.ap ${event.bssid}"
                is EngineEvent.Client -> "wifi.client ${event.mac}"
                is EngineEvent.Bt -> "bt.device ${event.mac}"
                is EngineEvent.FluxionLine -> "fluxion.log"
                is EngineEvent.FluxionCred -> "fluxion.cred"
                is EngineEvent.Result -> "result ${event.cmd}"
                is EngineEvent.ErrorEvent -> "error ${event.code}"
                is EngineEvent.Other -> "other"
            },
        )
        when (event) {
            is EngineEvent.Hello -> ui = ui.copy(version = event.version)
            is EngineEvent.State -> ui = ui.copy(
                mode = event.mode,
                dryRun = event.dryRun,
                allowlistCount = event.allowlistCount,
                stateError = event.error,
            )

            is EngineEvent.Log -> ui = ui.copy(
                logs = (ui.logs + LogInfo(event.message, event.level)).takeLast(80),
            )

            is EngineEvent.Ap -> {
                val ap = ApInfo(
                    bssid = event.bssid,
                    ssid = event.ssid,
                    channel = event.channel,
                    signal = event.signal,
                    security = event.security,
                )
                ui = ui.copy(
                    aps = (ui.aps.filterNot { it.bssid == ap.bssid } + ap)
                        .sortedByDescending { it.signal },
                    apEvents = ui.apEvents + 1,
                )
            }

            is EngineEvent.Client -> {
                val clientInfo = ClientInfo(
                    mac = event.mac,
                    bssid = event.bssid,
                    signal = event.signal,
                )
                ui = ui.copy(
                    clients = (ui.clients.filterNot { it.mac == clientInfo.mac } + clientInfo)
                        .sortedByDescending { it.signal },
                    selectedBssid = if (ui.selectedBssid.isBlank()) clientInfo.bssid else ui.selectedBssid,
                )
            }

            is EngineEvent.Bt -> {
                val bt = BtInfo(event.mac, event.name, event.rssi)
                ui = ui.copy(
                    btDevices = (ui.btDevices.filterNot { it.mac == bt.mac } + bt)
                        .sortedByDescending { it.rssi },
                )
            }

            is EngineEvent.FluxionLine -> ui = ui.copy(
                fluxionLines = (ui.fluxionLines + event.line).takeLast(120),
            )

            is EngineEvent.FluxionCred -> ui = ui.copy(
                creds = (ui.creds + "${event.username} (password length ${event.passwordLen})")
                    .takeLast(20),
            )

            is EngineEvent.Result -> ui = ui.copy(
                lastResult = "${event.cmd}: ${event.message} [${event.code}]",
            )

            is EngineEvent.ErrorEvent -> ui = ui.copy(
                lastResult = "ERROR ${event.code}: ${event.message}",
            )

            is EngineEvent.Other -> Unit
        }
    }

    val client = remember {
        EngineClient(
            onStatus = { status -> uiPost { ui = ui.copy(status = status) } },
            onMessage = { event -> uiPost { handle(event) } },
            onRaw = { line -> uiPost { ui = ui.copy(lastRaw = line) } },
        )
    }

    LaunchedEffect(client) {
        client.connect()
    }

    DisposableEffect(client) {
        onDispose { client.disconnect() }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Special Commander") },
                    actions = {
                        Text(
                            text = "${ui.status} · ${ui.mode}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                val tabs = listOf("Safety", "Wi-Fi", "Sniper", "Blue", "Portal", "Radar")
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) },
                        )
                    }
                }
                when (selectedTab) {
                    0 -> SafetyScreen(
                        ui = ui,
                        client = client,
                        onConnect = { scope.launch { client.connect() } },
                        onDisconnect = { client.disconnect() },
                    )

                    1 -> WifiScreen(
                        ui = ui,
                        client = client,
                        onReconnect = {
                            client.disconnect()
                            scope.launch { client.connect() }
                        },
                        onTestParse = {
                            val sample = """{"type":"event","event":"wifi.ap","data":{"bssid":"02:11:22:33:44:aa","ssid":"JSON-PARSE-TEST","channel":6,"signal":-42,"security":"WPA2"}}"""
                            val parsed = parseEngineLine(sample)
                            ui = ui.copy(
                                parseTest = when (parsed) {
                                    is EngineEvent.Ap -> "OK: ${parsed.ssid} ${parsed.bssid} ch${parsed.channel} ${parsed.signal}dBm ${parsed.security}"
                                    else -> "FAILED: $parsed"
                                },
                            )
                        },
                        onOpenSniper = { bssid ->
                            ui = ui.copy(selectedBssid = bssid)
                            selectedTab = 2
                        },
                        onOpenPortal = { bssid ->
                            ui = ui.copy(selectedBssid = bssid)
                            selectedTab = 4
                        },
                    )

                    2 -> SniperScreen(ui = ui, client = client)
                    3 -> BlueScreen(ui = ui, client = client)
                    4 -> PortalScreen(ui = ui, client = client)
                    5 -> RadarScreen(ui = ui)
                }
            }
        }
    }
}

@Composable
fun CardSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SafetyScreen(
    ui: UiState,
    client: EngineClient,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Engine") {
                InfoRow("Status", ui.status)
                InfoRow("Version", ui.version.ifBlank { "-" })
                InfoRow("Mode", ui.mode)
                InfoRow("Dry run", ui.dryRun.toString())
                InfoRow("Allow-list entries", ui.allowlistCount.toString())
                if (ui.stateError.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("State error: ${ui.stateError}")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConnect) { Text("Connect") }
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { client.send("safety.set_mode", mapOf("mode" to "active")) }) {
                        Text("Active mode")
                    }
                    OutlinedButton(onClick = { client.send("safety.set_mode", mapOf("mode" to "passive")) }) {
                        Text("Passive mode")
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { client.send("safety.get") }) { Text("Refresh state") }
            }
        }
        item {
            CardSection("Last result") {
                Text(ui.lastResult.ifBlank { "No command result yet." })
            }
        }
        item {
            CardSection("Engine log") {
                if (ui.logs.isEmpty()) {
                    Text("No log events yet.")
                } else {
                    ui.logs.takeLast(40).forEach { entry ->
                        Text("${entry.level.uppercase()}: ${entry.message}")
                    }
                }
            }
        }
    }
}

@Composable
fun WifiScreen(
    ui: UiState,
    client: EngineClient,
    onReconnect: () -> Unit,
    onTestParse: () -> Unit,
    onOpenSniper: (String) -> Unit,
    onOpenPortal: (String) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Wi-Fi control") {
                InfoRow("Connection", ui.status)
                InfoRow("AP events received", ui.apEvents.toString())
                InfoRow("Last engine event", ui.lastEvent.ifBlank { "-" })
                val raw = ui.lastRaw
                InfoRow("Last raw JSON", if (raw.length > 100) raw.take(100) + "..." else raw.ifBlank { "-" })
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { client.send("wifi.scan.start") }) { Text("Scan Wi-Fi") }
                    OutlinedButton(onClick = { client.send("wifi.scan.stop") }) { Text("Stop scan") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
                    OutlinedButton(onClick = onTestParse) { Text("Test JSON parse") }
                }
                InfoRow("Parse test", ui.parseTest.ifBlank { "not run" })
                Spacer(Modifier.height(6.dp))
                Text("Results arrive as live engine events. Select an AP to load clients or open Ghost Portal.")
            }
        }
        if (ui.aps.isEmpty()) {
            item { CardSection("Access points") { Text("No APs yet. Run a scan first.") } }
        } else {
            items(ui.aps, key = { it.bssid }) { ap ->
                CardSection("${ap.ssid} · ch ${ap.channel}") {
                    InfoRow("BSSID", ap.bssid)
                    InfoRow("Signal", "${ap.signal} dBm")
                    InfoRow("Security", ap.security.ifBlank { "-" })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            client.send("wifi.clients", mapOf("bssid" to ap.bssid))
                            onOpenSniper(ap.bssid)
                        }) { Text("Clients") }
                        OutlinedButton(onClick = onOpenPortal.bind(ap.bssid)) { Text("Portal") }
                    }
                }
            }
        }
    }
}

private fun ((String) -> Unit).bind(value: String): () -> Unit = { this(value) }

@Composable
fun SniperScreen(ui: UiState, client: EngineClient) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Death Sniper") {
                InfoRow("BSSID", ui.selectedBssid.ifBlank { "(select an AP in Wi-Fi)" })
                Spacer(Modifier.height(6.dp))
                Text("Tap DEAUTH to send immediately. This app applies no allow-list gate.")
            }
        }
        if (ui.clients.isEmpty()) {
            item { CardSection("Clients") { Text("No clients yet. Select an AP and load clients.") } }
        } else {
            items(ui.clients, key = { it.mac }) { entry ->
                CardSection(entry.mac) {
                    InfoRow("BSSID", entry.bssid.ifBlank { ui.selectedBssid })
                    InfoRow("Signal", "${entry.signal} dBm")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val bssid = entry.bssid.ifBlank { ui.selectedBssid }
                        if (bssid.isNotBlank()) {
                            client.send(
                                "deauth",
                                mapOf("client" to entry.mac, "bssid" to bssid),
                            )
                        }
                    }) { Text("DEAUTH") }
                }
            }
        }
        item {
            CardSection("Last result") {
                Text(ui.lastResult.ifBlank { "No deauth result yet." })
            }
        }
    }
}

@Composable
fun BlueScreen(ui: UiState, client: EngineClient) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Blue Sniffer") {
                Button(onClick = { client.send("bt.scan.start") }) { Text("Scan Bluetooth") }
                OutlinedButton(onClick = { client.send("bt.scan.stop") }) { Text("Stop scan") }
            }
        }
        if (ui.btDevices.isEmpty()) {
            item { CardSection("Devices") { Text("No Bluetooth devices yet.") } }
        } else {
            items(ui.btDevices, key = { it.mac }) { device ->
                CardSection(device.name) {
                    InfoRow("MAC", device.mac)
                    InfoRow("RSSI", "${device.rssi} dBm")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        client.send(
                            "bt.spoof",
                            mapOf("mac" to device.mac, "scope" to "bluetooth", "label" to device.name),
                        )
                    }) { Text("SPOOF MAC") }
                }
            }
        }
    }
}

@Composable
fun PortalScreen(ui: UiState, client: EngineClient) {
    var targetText by remember { mutableStateOf(ui.selectedBssid) }
    var pendingTarget by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(ui.selectedBssid) {
        if (ui.selectedBssid.isNotBlank()) targetText = ui.selectedBssid
    }

    val canConfirm = pendingTarget.isNotBlank() &&
        typed.trim().equals(pendingTarget, ignoreCase = true)

    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Ghost Portal / Handshake Snooper") {
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("Target BSSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pendingTarget = targetText.trim()
                        typed = ""
                    }) { Text("Launch Fluxion") }
                    OutlinedButton(onClick = { client.send("fluxion.stop") }) { Text("Stop Fluxion") }
                }
                if (pendingTarget.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("TYPE THIS TO CONFIRM: $pendingTarget")
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Type BSSID to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Button(
                        enabled = canConfirm,
                        onClick = {
                            client.send("fluxion.start", mapOf("bssid" to pendingTarget))
                            pendingTarget = ""
                            typed = ""
                        },
                    ) { Text("Confirm Fluxion") }
                }
            }
        }
        item {
            CardSection("Fluxion log") {
                if (ui.fluxionLines.isEmpty()) {
                    Text("No Fluxion output yet.")
                } else {
                    ui.fluxionLines.takeLast(40).forEach { Text(it) }
                }
            }
        }
        item {
            CardSection("Captured credentials") {
                if (ui.creds.isEmpty()) {
                    Text("No credentials seen.")
                } else {
                    ui.creds.forEach { Text(it) }
                }
            }
        }
        item {
            CardSection("Last result") {
                Text(ui.lastResult.ifBlank { "No Fluxion result yet." })
            }
        }
    }
}

@Composable
fun RadarScreen(ui: UiState) {
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            CardSection("Passive Radar (listen-only)") {
                Text("This screen only displays received events. It does not send attack commands.")
            }
        }
        item {
            CardSection("Access points (${ui.aps.size})") {
                ui.aps.forEach { Text("${it.ssid}  ${it.bssid}  ${it.signal} dBm") }
            }
        }
        item {
            CardSection("Clients (${ui.clients.size})") {
                ui.clients.forEach { Text("${it.mac}  ${it.bssid}  ${it.signal} dBm") }
            }
        }
        item {
            CardSection("Log") {
                ui.logs.takeLast(40).forEach { Text("${it.level.uppercase()}: ${it.message}") }
            }
        }
    }
}
