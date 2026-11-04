package com.googlyeyes.companion

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.googlyeyes.core.CompanionPrefs
import com.googlyeyes.core.FocusStatus
import com.googlyeyes.core.Mood
import com.googlyeyes.core.Packet
import com.googlyeyes.core.WireJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Connects the voice, the Mac link, the history and the settings to each other. */
object Wiring {
    fun connect(g: AppGraph) {
        val link = g.link
        val voice = g.voice
        val store = g.store
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Keys and tools come from the Mac.
        voice.requestToken = { done -> link.request(Packet(command = "realtimeToken")) { done(it?.text) } }
        voice.runTool = { name, args, done ->
            link.request(Packet(command = "tool", tool = name, text = args), timeoutMs = 60_000) { reply ->
                done(reply?.text ?: "The Mac didn't answer.", reply?.image)
            }
        }

        // His words go to the Mac's speech bubble (which the Mac hides in silent mode).
        voice.onCaption = { text, done ->
            if (!g.prefs.prefs.value.silent) link.send(Packet(command = if (done) "captionDone" else "caption", text = text))
        }

        // History.
        voice.onSessionStart = { store.start() }
        voice.onSessionEnd = { store.end() }
        voice.onUserTurn = { item, asked -> store.placeholder(item, asked) }
        voice.onUserWords = { item, text, asked -> store.heard(item, text, asked) }
        voice.onTyped = { store.typed(it) }
        voice.onReply = { store.reply(it) }
        voice.onReport = { store.report(it) }
        voice.onCodexNote = { store.codex(it) }
        voice.resumeContext = { store.recentText(g.prefs.prefs.value.name) }

        // The face.
        link.onFace = { g.animator.receive(it, System.nanoTime() / 1e9) }
        g.animator.localTalk = { voice.chirpLevel }
        voice.onStateChange = { state ->
            val a = g.animator
            when (state) {
                LiveVoice.State.ASLEEP -> { a.awake = false; a.localMood = null; link.send(Packet(command = "asleep")) }
                LiveVoice.State.WAKING -> { a.awake = true; a.localMood = Mood.HAPPY }
                LiveVoice.State.LISTENING -> { a.awake = true; a.localMood = null; link.send(Packet(command = "awake")) }
                LiveVoice.State.ASKING -> { a.awake = true; a.localMood = Mood.LISTENING; link.send(Packet(command = "awake")) }
                LiveVoice.State.THINKING -> a.localMood = Mood.THINKING
                LiveVoice.State.SPEAKING -> a.localMood = Mood.TALKING
            }
        }

        // Commands from the Mac.
        link.onFocusState = { g.focus.receive(it) }
        link.onCommand = { command, text ->
            when (command) {
                "wake" -> voice.wake()
                "sleep" -> voice.sleep()
                "prefs" -> text?.let { receivePrefs(g, it) }
                "codexNote" -> voice.codexNote(text.orEmpty())
                "codexChirp" -> { voice.chirpAlone(); text?.let { store.codex(it) } }
                "focusChirp" -> {
                    val message = text ?: "Are you sure you're on task right now?"
                    g.focus.receivedNudge(message)
                    store.nudge(message)
                    voice.chirpAlone()
                }
            }
        }
        link.onAuthenticated = {
            link.request(Packet(command = "getPrefs")) { reply -> reply?.text?.let { receivePrefs(g, it) } }
        }
        scope.launch {
            link.connected.drop(1).collect { connected ->
                g.focus.connectionChanged(connected)
                if (!connected) voice.sleep()
            }
        }

        // Stop everything in the background, like the iPhone app: no listening while you're not looking.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { link.start() }
            override fun onStop(owner: LifecycleOwner) {
                if (g.focus.current.value?.status == FocusStatus.RUNNING) g.focus.command("focusPause")
                voice.sleep()
                link.stop()
            }
        })
    }

    private fun receivePrefs(g: AppGraph, text: String) {
        runCatching { WireJson.decodeFromString(CompanionPrefs.serializer(), text) }.getOrNull()?.let { g.prefs.update(it) }
    }
}

/** What the screens can change. Shared settings go to the Mac, which keeps the real copy. */
class Actions(private val g: AppGraph) {
    fun setName(name: String) = change(g.prefs.prefs.value.copy(name = name.trim().ifEmpty { "Bluey" }.take(24))) {
        put("name", JsonPrimitive(it.name))
    }

    fun setHue(hue: Double?) = change(g.prefs.prefs.value.copy(hue = hue)) {
        put("hue", hue?.let { h -> JsonPrimitive(h) } ?: JsonNull)
    }

    fun setSilent(on: Boolean) = change(g.prefs.prefs.value.copy(silent = on)) { put("silent", JsonPrimitive(on)) }

    fun setFocusEnabled(on: Boolean) = change(g.prefs.prefs.value.copy(focusEnabled = on)) { put("focusEnabled", JsonPrimitive(on)) }

    /** Updates the phone right away, then tells the Mac. The Mac's answer is the final word. */
    private fun change(local: CompanionPrefs, fields: kotlinx.serialization.json.JsonObjectBuilder.(CompanionPrefs) -> Unit) {
        g.prefs.update(local)
        val json = buildJsonObject { fields(local) }
        g.link.request(Packet(command = "setPrefs", text = json.toString())) { reply ->
            reply?.text?.let { text ->
                runCatching { WireJson.decodeFromString(CompanionPrefs.serializer(), text) }.getOrNull()?.let { g.prefs.update(it) }
            }
        }
    }
}
