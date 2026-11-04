package com.googlyeyes.companion

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.googlyeyes.core.FaceState
import com.googlyeyes.core.GooglyService
import com.googlyeyes.core.LineSession
import com.googlyeyes.core.Packet
import com.googlyeyes.core.PairingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Finds the paired Mac on the local network (Bonjour, via Android's NSD) and keeps one encrypted
 * connection open. Everything except the socket reads and writes happens on the main thread.
 */
class MacLink(private val context: Context, private val prefs: PrefsRepository) {
    private val main = Handler(Looper.getMainLooper())
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _macName = MutableStateFlow<String?>(null)
    val macName: StateFlow<String?> = _macName.asStateFlow()
    private val _status = MutableStateFlow("Looking for your Mac…")
    /** A short human description of what the link is doing, for the settings screen. */
    val status: StateFlow<String> = _status.asStateFlow()
    private val _paired = MutableStateFlow(prefs.pairing != null)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    var onFace: ((FaceState) -> Unit)? = null
    /** Commands from the Mac that aren't replies, like "wake", "sleep", "prefs", "focusChirp". */
    var onCommand: ((String, String?) -> Unit)? = null
    var onFocusState: ((String) -> Unit)? = null
    var onAuthenticated: (() -> Unit)? = null

    private var started = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private val found = LinkedHashMap<String, NsdServiceInfo>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var multicastLock: WifiManager.MulticastLock? = null

    private var socket: Socket? = null
    private var session: LineSession? = null
    private var writer = Executors.newSingleThreadExecutor()
    private var lastEndpoint: Pair<InetAddress, Int>? = null
    private var connecting = false
    private val waiting = HashMap<String, (Packet?) -> Unit>()
    private var retry: Runnable? = null

    // MARK: Start and stop

    fun start() {
        if (started) return
        started = true
        if (prefs.pairing == null) { _status.value = "Not paired yet. Scan the QR code from your Mac."; return }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("googly-discovery").apply { setReferenceCounted(false); acquire() }
        startDiscovery()
        lastEndpoint?.let { (host, port) -> connect(host, port) }
    }

    fun stop() {
        started = false
        retry?.let { main.removeCallbacks(it) }
        stopDiscovery()
        multicastLock?.release()
        multicastLock = null
        disconnect("stopped")
    }

    /** Saves a newly scanned pairing and reconnects with it. */
    fun pair(record: PairingRecord) {
        prefs.pairing = record
        _paired.value = true
        lastEndpoint = null
        stop()
        start()
    }

    fun forget() {
        prefs.pairing = null
        _paired.value = false
        lastEndpoint = null
        stop()
        _status.value = "Not paired yet. Scan the QR code from your Mac."
    }

    // MARK: Discovery

    private fun startDiscovery() {
        if (discovery != null) return
        found.clear()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                main.post { if (!_connected.value) _status.value = "Looking for your Mac on this Wi-Fi…" }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    found[info.serviceName] = info
                    if (!_connected.value && !connecting) enqueueResolve(info)
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) { main.post { found.remove(info.serviceName) } }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                main.post { discovery = null; scheduleRetry(5000) }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching { nsd.discoverServices(GooglyService.TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discovery = null; scheduleRetry(5000) }
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        resolveQueue.clear()
        resolving = false
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        if (resolveQueue.none { it.serviceName == info.serviceName }) resolveQueue.addLast(info)
        resolveNext()
    }

    /** NSD can only resolve one service at a time, so they queue. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving || _connected.value || connecting) return
        val info = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post { resolving = false; resolveNext() }
            }
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                main.post {
                    resolving = false
                    if (matches(resolved) && resolved.host != null) connect(resolved.host, resolved.port)
                    else resolveNext()
                }
            }
        })
    }

    /** The Mac advertises a fingerprint of the pairing secret; only connect to the one we're paired with. */
    private fun matches(info: NsdServiceInfo): Boolean {
        val pairing = prefs.pairing ?: return false
        val id = info.attributes[GooglyService.ID_KEY]?.let { String(it) }
        if (id != null) return id == pairing.identifier
        // Some phones drop TXT records. Fall back to the Mac's name; the handshake still checks the secret.
        return info.serviceName == pairing.service
    }

    // MARK: Connection

    private fun connect(host: InetAddress, port: Int) {
        val pairing = prefs.pairing ?: return
        val secret = pairing.key ?: return
        if (connecting || session != null) return
        connecting = true
        _status.value = "Connecting to ${pairing.service}…"
        lastEndpoint = host to port
        val writer = Executors.newSingleThreadExecutor().also { this.writer = it }
        Thread {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), 5000)
            } catch (e: Exception) {
                runCatching { socket.close() }
                main.post { connecting = false; connectionFailed("Couldn't reach the Mac") }
                return@Thread
            }
            val output: OutputStream = socket.getOutputStream()
            main.post {
                if (!started) { runCatching { socket.close() }; connecting = false; return@post }
                this.socket = socket
                val line = LineSession(secret, server = false) { bytes ->
                    writer.execute { runCatching { output.write(bytes); output.flush() }.onFailure { main.post { disconnect("write failed") } } }
                }
                line.onAuthenticated = { authenticated(line) }
                line.onPacket = { handle(it) }
                line.onFailure = { reason -> Log.w(TAG, "link failure: $reason"); disconnect(reason) }
                session = line
                connecting = false
                // Authentication must finish quickly, or this isn't our Mac.
                main.postDelayed({ if (session === line && !line.authenticated) disconnect("handshake timed out") }, 8000)
            }
            val buffer = ByteArray(64 * 1024)
            try {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    val chunk = buffer.copyOf(n)
                    main.post { if (this.socket === socket) session?.receive(chunk) }
                }
            } catch (_: Exception) {
            }
            main.post { if (this.socket === socket) disconnect("closed") }
        }.start()
    }

    private fun authenticated(line: LineSession) {
        if (session !== line) return
        _connected.value = true
        _status.value = "Connected to ${prefs.pairing?.service ?: "your Mac"}"
        line.send(Packet(hello = deviceName()))
        line.send(Packet(command = "focusSync"))
        onAuthenticated?.invoke()
    }

    private fun handle(packet: Packet) {
        packet.hello?.let { _macName.value = it }
        packet.face?.let { onFace?.invoke(it) }
        val command = packet.command ?: return
        if (command == "focusState") { packet.text?.let { onFocusState?.invoke(it) }; return }
        val callID = packet.callID
        val done = if (callID != null) waiting.remove(callID) else null
        if (done != null) done(packet) else onCommand?.invoke(command, packet.text)
    }

    private fun connectionFailed(reason: String) {
        _status.value = "$reason. Retrying…"
        lastEndpoint = null
        scheduleRetry(2000)
    }

    private fun disconnect(reason: String) {
        val wasConnected = _connected.value
        session = null
        runCatching { socket?.close() }
        socket = null
        writer.shutdown()
        connecting = false
        _connected.value = false
        _macName.value = null
        val pending = waiting.values.toList()
        waiting.clear()
        pending.forEach { it(null) }
        if (started) {
            _status.value = if (wasConnected) "Lost the Mac. Reconnecting…" else "Looking for your Mac…"
            scheduleRetry(1500)
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retry?.let { main.removeCallbacks(it) }
        val work = Runnable {
            if (!started || _connected.value || connecting) return@Runnable
            val endpoint = lastEndpoint
            if (endpoint != null) connect(endpoint.first, endpoint.second)
            else {
                // Re-run discovery from scratch: the Mac may have restarted on a new port.
                stopDiscovery()
                startDiscovery()
                found.values.forEach { enqueueResolve(it) }
            }
        }
        retry = work
        main.postDelayed(work, delayMs)
    }

    // MARK: Sending

    fun send(packet: Packet) {
        session?.takeIf { it.authenticated }?.send(packet)
    }

    /** Sends a request and calls back with the Mac's reply, or null if the Mac went away or took too long. */
    fun request(packet: Packet, timeoutMs: Long = 12_000, done: (Packet?) -> Unit) {
        val line = session
        if (line == null || !_connected.value) { done(null); return }
        val id = UUID.randomUUID().toString()
        waiting[id] = done
        main.postDelayed({ waiting.remove(id)?.invoke(null) }, timeoutMs)
        line.send(packet.copy(callID = id))
    }

    private fun deviceName(): String =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object { private const val TAG = "MacLink" }
}
