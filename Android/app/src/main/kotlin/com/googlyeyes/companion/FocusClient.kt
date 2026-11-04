package com.googlyeyes.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.googlyeyes.core.FocusSession
import com.googlyeyes.core.FocusStatus
import com.googlyeyes.core.FocusSync
import com.googlyeyes.core.Packet
import com.googlyeyes.core.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Focus mode, mirrored from the Mac (which runs the timer and the screen checks). Off unless the user turns
 * Focus mode on. The phone pings the Mac every second while connected, which is how the Mac knows the
 * phone is still there.
 */
class FocusClient(context: Context) {
    private val main = Handler(Looper.getMainLooper())

    private val _current = MutableStateFlow<FocusSession?>(null)
    val current: StateFlow<FocusSession?> = _current.asStateFlow()
    private val _history = MutableStateFlow<List<FocusSession>>(emptyList())
    val history: StateFlow<List<FocusSession>> = _history.asStateFlow()
    private val _developerMode = MutableStateFlow(false)
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()
    private val _nudge = MutableStateFlow("")
    val nudge: StateFlow<String> = _nudge.asStateFlow()
    /** When the last state arrived, to run the countdown smoothly between updates. */
    var receivedAt = 0L
        private set

    private var revision = -1
    private var link: MacLink? = null

    fun attach(link: MacLink) {
        this.link = link
        val ping = object : Runnable {
            override fun run() {
                if (link.connected.value) link.send(Packet(command = "focusPing"))
                main.postDelayed(this, 1000)
            }
        }
        main.post(ping)
    }

    fun connectionChanged(connected: Boolean) {
        revision = -1
        if (connected) link?.send(Packet(command = "focusSync"))
    }

    fun receive(text: String) {
        val state = runCatching { WireJson.decodeFromString(FocusSync.serializer(), text) }.getOrNull() ?: return
        if (state.revision <= revision) return
        revision = state.revision
        receivedAt = System.currentTimeMillis()
        if (_current.value?.id != state.current?.id) _nudge.value = ""
        _current.value = state.current
        _history.value = state.history
        _developerMode.value = state.developerMode ?: false
    }

    fun receivedNudge(text: String) {
        _nudge.value = text
        main.postDelayed({ if (_nudge.value == text) _nudge.value = "" }, 10_000)
    }

    /** Seconds left, counting down smoothly between the Mac's updates. */
    fun remainingNow(): Double {
        val session = _current.value ?: return 0.0
        if (session.status != FocusStatus.RUNNING) return session.remaining
        val rate = if (session.developerMode == true) 4.0 else 1.0
        val since = (System.currentTimeMillis() - receivedAt) / 1000.0
        return maxOf(0.0, session.remaining - since * rate)
    }

    fun command(command: String, task: String? = null, minutes: Double = 30.0, developerMode: Boolean? = null) {
        val link = link
        if (link == null || !link.connected.value) { _message.value = "Connect to your Mac first."; return }
        val args = buildJsonObject {
            put("task", task ?: "")
            put("minutes", minutes)
            if (developerMode != null) put("developerMode", JsonPrimitive(developerMode))
        }
        link.request(Packet(command = command, text = args.toString())) { reply ->
            _message.value = reply?.text ?: "The Mac didn't answer."
        }
    }
}
