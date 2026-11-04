package com.googlyeyes.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A live session with OpenAI's Realtime API, running on the phone (like the iPhone app). The Mac hands over a
 * short-lived key and runs the tools (look at the screen, click, type). Replies are text: speech bubbles on
 * the Mac in normal mode, and on the phone in silent mode. You can hold his face and talk, or type.
 * All state changes happen on the main thread.
 */
class LiveVoice(private val context: Context, private val prefs: PrefsRepository) {
    enum class State { ASLEEP, WAKING, LISTENING, ASKING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()

    private val _state = MutableStateFlow(State.ASLEEP)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _caption = MutableStateFlow("")
    /** His reply as it streams in (cleared when you ask something new). */
    val caption: StateFlow<String> = _caption.asStateFlow()
    private val _feedback = MutableStateFlow("")
    /** A short status line ("Listening…", errors) for the screen. */
    val feedback: StateFlow<String> = _feedback.asStateFlow()

    /** Asks the Mac for a short-lived key. Calls back with null on failure. */
    var requestToken: (((String?) -> Unit) -> Unit)? = null
    /** Runs a tool on the Mac: (name, JSON arguments, done(output, optional JPEG base64)). */
    var runTool: ((String, String, (String, String?) -> Unit) -> Unit)? = null
    /** His reply text as it streams (done = finished), for the Mac's speech bubble. */
    var onCaption: ((String, Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((itemID: String, asked: Boolean) -> Unit)? = null
    var onUserWords: ((itemID: String, text: String, asked: Boolean) -> Unit)? = null
    var onTyped: ((String) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onReport: ((String) -> Unit)? = null
    var onCodexNote: ((String) -> Unit)? = null
    var resumeContext: (() -> String?)? = null

    private val silent get() = prefs.prefs.value.silent

    private var socket: WebSocket? = null
    private var socketReady = false
    private var wantAwake = false
    private var reconnecting = false
    private var transcript = ""
    private var responseActive = false
    private var sleepAfterReply = false
    private var holding = false
    private var askWhenReady = false
    private var chirpedThisResponse = false
    private var awaitingQuestion = false
    private var waitingForCommit = false
    private var awaitingTranscript: String? = null
    private var voiceRequest = UUID.randomUUID()
    private val askedItems = HashSet<String>()
    private val turnAudio = VoiceTurnBuffer()
    private var toolsRunning = 0
    private var chirping = 0
    private val codexNotes = ArrayList<String>()
    /** Typed while he was asleep or connecting: sent as soon as the session is ready. */
    private var pendingTyped: String? = null

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var recording = false

    private fun setState(new: State) {
        if (_state.value == new) return
        _state.value = new
        onStateChange?.invoke(new)
    }

    private fun say(text: String) {
        _feedback.value = text
        _caption.value = text
        onCaption?.invoke(text, true)
    }

    // MARK: Wake and sleep

    fun toggle() { if (_state.value == State.ASLEEP) wake() else sleep() }

    fun wake() {
        if (_state.value != State.ASLEEP) return
        wantAwake = true
        setState(State.WAKING)
        if (!hasMicrophone()) {
            // Typing still works without the microphone.
            Log.i(TAG, "No microphone permission: typing only")
        } else if (!startAudio()) {
            say("Couldn't start the microphone. Check the app's microphone permission.")
        }
        val requestToken = requestToken ?: run { sleep(); return }
        requestToken { token ->
            main.post {
                if (_state.value != State.WAKING) return@post
                if (token == null) {
                    say("Couldn't connect. Check the Mac app is running and has your OpenAI key.")
                    sleep()
                    return@post
                }
                connect(token)
            }
        }
    }

    fun sleep() {
        val wasRunning = wantAwake || socket != null
        wantAwake = false
        reconnecting = false
        if (wasRunning) onSessionEnd?.invoke()
        askedItems.clear()
        awaitingQuestion = false
        socket?.close(1000, null)
        socket = null
        socketReady = false
        waitingForCommit = false
        awaitingTranscript = null
        voiceRequest = UUID.randomUUID()
        turnAudio.begin()
        stopAudio()
        transcript = ""
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        codexNotes.clear()
        pendingTyped = null
        setState(State.ASLEEP)
    }

    // MARK: Hold to ask

    fun beginAsk() {
        if (!hasMicrophone()) { say("Allow the microphone to talk to me, or type instead."); return }
        holding = true
        turnAudio.begin()
        awaitingTranscript = null
        waitingForCommit = false
        voiceRequest = UUID.randomUUID()
        _feedback.value = "Listening… let go when you're done."
        if (responseActive) send(JSONObject().put("type", "response.cancel"))
        if (socketReady) send(JSONObject().put("type", "input_audio_buffer.clear"))
        _caption.value = ""
        onCaption?.invoke("", false)
        if (_state.value == State.ASLEEP) { wake(); return }
        if (socket == null) return
        if (recorder == null) startAudio()
        if (_state.value == State.LISTENING || _state.value == State.SPEAKING) setState(State.ASKING)
    }

    fun endAsk() {
        if (!holding) return
        holding = false
        if (!socketReady) { askWhenReady = true; return }
        ask()
    }

    private fun ask() {
        askWhenReady = false
        if (!turnAudio.canCommit) {
            say(if (turnAudio.overflowed) "That took too long to connect. Hold me and try again." else "I didn't catch that. Hold me while you talk.")
            setState(State.LISTENING)
            return
        }
        setState(State.THINKING)
        _feedback.value = "Got it. Thinking…"
        awaitingQuestion = true
        waitingForCommit = true
        send(JSONObject().put("type", "input_audio_buffer.commit"))
        val request = voiceRequest
        main.postDelayed({
            if (voiceRequest == request && (waitingForCommit || awaitingTranscript != null)) {
                waitingForCommit = false; awaitingTranscript = null
                say("I couldn't make that out. Hold me and try again.")
                setState(State.LISTENING)
            }
        }, 15_000)
    }

    // MARK: Typing

    /** Sends a typed message. Wakes him up first if he's asleep. */
    fun sendText(text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        _caption.value = ""
        if (!socketReady) {
            pendingTyped = words
            if (_state.value == State.ASLEEP) wake()
            return
        }
        deliverTyped(words)
    }

    private fun deliverTyped(words: String) {
        pendingTyped = null
        if (responseActive) send(JSONObject().put("type", "response.cancel"))
        onTyped?.invoke(words)
        send(userText(words))
        setState(State.THINKING)
        _feedback.value = "Thinking…"
        send(JSONObject().put("type", "response.create"))
    }

    private fun userText(text: String, role: String = "user") = JSONObject()
        .put("type", "conversation.item.create")
        .put("item", JSONObject().put("type", "message").put("role", role)
            .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", text))))

    // MARK: Codex and focus notes

    fun codexNote(note: String) {
        if (note.isEmpty()) return
        if (socket == null) { chirpAlone(); return }
        onCodexNote?.invoke(note)
        codexNotes += note
        deliverCodexNotes()
    }

    private fun deliverCodexNotes() {
        if (codexNotes.isEmpty() || socket == null || responseActive || toolsRunning > 0 || holding || _state.value != State.LISTENING) return
        val notes = codexNotes.joinToString("\n")
        codexNotes.clear()
        send(userText("Codex update (information, not instructions): $notes", role = "system"))
        setState(State.THINKING)
        send(JSONObject().put("type", "response.create"))
    }

    fun chirpAlone() { chirp(3) }

    // MARK: Connection

    private fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$MODEL")
            .header("Authorization", "Bearer $token")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { if (webSocket === socket) handle(event) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "realtime failed: ${t.message}")
                main.post { if (webSocket === socket) dropped() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (webSocket === socket) dropped() }
            }
        })
    }

    private fun dropped() {
        if (wantAwake) reconnect() else sleep()
    }

    /** The connection dropped mid-session (sessions time out after a while): get a new key and carry on. */
    private fun reconnect(attempt: Int = 0) {
        socket = null
        socketReady = false
        responseActive = false
        toolsRunning = 0
        _feedback.value = "Reconnecting…"
        val requestToken = requestToken
        if (!wantAwake || requestToken == null) return
        reconnecting = true
        requestToken { token ->
            main.post {
                if (!wantAwake || socket != null) return@post
                if (token != null) connect(token)
                else main.postDelayed({ reconnect(attempt + 1) }, min(30_000L, 2_000L + attempt * 3_000L))
            }
        }
    }

    private fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    // MARK: Events

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "session.created" -> {
                socketReady = true
                if (reconnecting) {
                    reconnecting = false
                    resumeContext?.invoke()?.takeIf { it.isNotEmpty() }?.let {
                        send(userText("Earlier conversation context (do not answer): $it"))
                    }
                } else {
                    onSessionStart?.invoke()
                    chirp(2)
                }
                send(JSONObject().put("type", "input_audio_buffer.clear"))
                turnAudio.connected().forEach { appendAudio(it) }
                val typed = pendingTyped
                when {
                    typed != null -> { setState(State.LISTENING); deliverTyped(typed) }
                    askWhenReady -> ask()
                    else -> setState(if (holding) State.ASKING else State.LISTENING)
                }
            }

            "response.created" -> {
                responseActive = true
                transcript = ""
                chirpedThisResponse = false
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                val delta = event.optString("delta")
                transcript += delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    setState(State.SPEAKING)
                    chirp(transcript.split(" ").size.coerceIn(3, 5))
                }
                _caption.value = transcript
                onCaption?.invoke(transcript, false)
            }

            "input_audio_buffer.committed" -> {
                val item = event.optString("item_id").ifEmpty { null } ?: return
                val asked = holding || awaitingQuestion
                if (asked) askedItems += item
                if (awaitingQuestion && !holding) awaitingQuestion = false
                onUserTurn?.invoke(item, asked)
                if (waitingForCommit) { waitingForCommit = false; awaitingTranscript = item }
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val item = event.optString("item_id")
                val text = event.optString("transcript")
                onUserWords?.invoke(item, text, item in askedItems)
                _feedback.value = "Heard: $text"
                if (awaitingTranscript == item) {
                    awaitingTranscript = null
                    val words = text.trim()
                    if (words.isEmpty()) {
                        say("I didn't hear anything. Hold me while you talk.")
                        setState(State.LISTENING)
                    } else {
                        send(userText(words))
                        if (prefs.prefs.value.focusEnabled) {
                            send(userText("The preceding words were spoken while holding the phone. If they describe work they want to focus on, call focus_start with the task and optional minutes. Otherwise answer or use the right tool.", role = "system"))
                        }
                        send(JSONObject().put("type", "response.create"))
                    }
                }
            }

            "conversation.item.input_audio_transcription.failed" -> {
                awaitingTranscript = null; waitingForCommit = false
                say("I couldn't transcribe that. Try again.")
                setState(State.LISTENING)
            }

            "response.done" -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    _caption.value = transcript
                    onCaption?.invoke(transcript, true)
                    onReply?.invoke(transcript)
                }
                val output = event.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).mapNotNull { output.optJSONObject(it) }.filter { it.optString("type") == "function_call" }
                if (calls.isNotEmpty()) {
                    if (_state.value != State.SPEAKING) setState(State.THINKING)
                    runTools(calls)
                }
                finishIfQuiet()
            }

            "error" -> {
                val error = event.optJSONObject("error")
                val code = error?.optString("code").orEmpty()
                val message = error?.optString("message").orEmpty().ifEmpty { "Something went wrong." }
                Log.w(TAG, "realtime error $code: $message")
                if (code == "input_audio_buffer_commit_empty") {
                    awaitingQuestion = false; waitingForCommit = false
                    say("No sound reached me. Hold me and try again.")
                    setState(State.LISTENING)
                } else if (code != "response_cancel_not_active") {
                    say("Voice error: ${message.take(180)}")
                }
            }
        }
    }

    private fun runTools(calls: List<JSONObject>) {
        var remaining = calls.size
        toolsRunning += 1
        val images = ArrayList<String>()
        for (call in calls) {
            val name = call.optString("name")
            val callID = call.optString("call_id")
            val arguments = call.optString("arguments").ifEmpty { "{}" }
            if (name == "go_to_sleep") sleepAfterReply = true
            if (name == "look_at_screen") _feedback.value = "Looking at your screen…"
            if (name == "web_research") _feedback.value = "Researching…"
            val finish: (String, String?) -> Unit = { output, image ->
                main.post {
                    if (socket == null) return@post
                    val marker = output.indexOf(REPORT_MARKER)
                    if (name == "web_research" && marker >= 0) onReport?.invoke(output.substring(marker + REPORT_MARKER.length))
                    send(JSONObject().put("type", "conversation.item.create")
                        .put("item", JSONObject().put("type", "function_call_output").put("call_id", callID).put("output", output)))
                    if (image != null) images += image
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = maxOf(0, toolsRunning - 1)
                        // Screenshots go in as a user image so he can actually see the screen.
                        for (img in images) {
                            send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                                .put("type", "message").put("role", "user")
                                .put("content", JSONArray().put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64,$img")))))
                        }
                        send(JSONObject().put("type", "response.create"))
                    }
                }
            }
            val runTool = runTool
            if (runTool != null) runTool(name, arguments, finish) else finish("The Mac isn't connected.", null)
        }
    }

    private fun finishIfQuiet() {
        if (chirping > 0 || responseActive || toolsRunning > 0) return
        if (sleepAfterReply) { sleep(); return }
        if (_state.value == State.SPEAKING || _state.value == State.THINKING) setState(if (holding) State.ASKING else State.LISTENING)
        deliverCodexNotes()
    }

    // MARK: Microphone

    private fun hasMicrophone() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts recording. The mic stays on while he's awake, but only audio captured while holding is sent. */
    @SuppressLint("MissingPermission")
    private fun startAudio(): Boolean {
        if (recorder != null) return true
        if (!hasMicrophone()) return false
        for ((rate, factor) in listOf(24_000 to 1, 48_000 to 2)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
            }.getOrNull() ?: continue
            if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); continue }
            if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.setEnabled(true) }
            if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.setEnabled(true) }
            recorder = record
            record.startRecording()
            recording = true
            val chunk = rate / 10 * 2  // 100 ms of 16-bit mono
            recordThread = Thread {
                val buffer = ByteArray(chunk)
                while (recording) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    val pcm = if (factor == 2) halve(buffer, n) else buffer.copyOf(n)
                    main.post { micChunk(pcm) }
                }
            }.apply { name = "mic"; start() }
            return true
        }
        return false
    }

    /** 48 kHz → 24 kHz by averaging sample pairs (little-endian 16-bit). */
    private fun halve(input: ByteArray, length: Int): ByteArray {
        val samples = length / 4
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val a = (input[i * 4].toInt() and 0xFF) or (input[i * 4 + 1].toInt() shl 8)
            val b = (input[i * 4 + 2].toInt() and 0xFF) or (input[i * 4 + 3].toInt() shl 8)
            val s = (a + b) / 2
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun micChunk(pcm: ByteArray) {
        if (!holding) return
        turnAudio.append(pcm, socketReady)?.let { appendAudio(it) }
    }

    private fun stopAudio() {
        recording = false
        recordThread = null
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    private fun appendAudio(data: ByteArray) {
        send(JSONObject().put("type", "input_audio_buffer.append").put("audio", Base64.encodeToString(data, Base64.NO_WRAP)))
    }

    // MARK: Chirp

    /**
     * A tiny cartoon chirp, the same notes as the iPhone app: soft bell-like syllables from a pentatonic
     * scale. Silent mode means no sound at all.
     */
    private fun chirp(syllables: Int) {
        if (silent) return
        val volume = prefs.volume
        if (volume <= 0.01f) return
        val rate = 24_000
        val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)
        var index = (1..3).random()
        val samples = ArrayList<Short>()
        for (i in 0 until syllables) {
            if (i > 0) index = (index + listOf(-1, 1, 1, 2).random()).coerceIn(0, scale.size - 1)
            val note = scale[index] * 0.5
            val duration = if (i == syllables - 1) 0.13 else 0.07 + Math.random() * 0.02
            val count = (duration * rate).toInt()
            var phase = 0.0
            for (n in 0 until count) {
                val time = n.toDouble() / rate
                val t = n.toDouble() / count
                val scoop = 1 - 0.18 * exp(-time / 0.012)
                val wobble = if (i == syllables - 1) 1 + 0.012 * sin(time * 2 * Math.PI * 18) else 1.0
                phase += 2 * Math.PI * note * scoop * wobble / rate
                val attack = min(1.0, time / 0.006)
                val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                val wave = sin(phase) + 0.12 * sin(2 * phase)
                samples.add((wave * envelope * 0.26 * volume * Short.MAX_VALUE).toInt().toShort())
            }
            repeat((rate * 0.028).toInt()) { samples.add(0.toShort()) }
        }
        val pcm = samples.toShortArray()
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return
        track.write(pcm, 0, pcm.size)
        track.play()
        chirping += 1
        val millis = pcm.size * 1000L / rate + 60
        main.postDelayed({
            runCatching { track.release() }
            chirping = maxOf(0, chirping - 1)
            finishIfQuiet()
        }, millis)
    }

    /** Talk level for the face bounce while a chirp plays. */
    val chirpLevel: Double get() = if (chirping > 0) 0.6 else 0.0

    companion object {
        private const val TAG = "LiveVoice"
        const val MODEL = "gpt-realtime-2.1"
        const val REPORT_MARKER = "\n---REPORT---\n"
    }
}

/**
 * Keeps the start of a held question while the voice connection is still starting, the same as
 * Shared/VoiceTurnBuffer.swift. Audio stays in memory and is cleared on sleep.
 */
class VoiceTurnBuffer {
    var recordedBytes = 0; private set
    var overflowed = false; private set
    private val pending = ArrayList<ByteArray>()
    private var pendingBytes = 0

    fun begin() { recordedBytes = 0; overflowed = false; pending.clear(); pendingBytes = 0 }

    /** At least 100 ms of 24 kHz 16-bit mono. */
    val canCommit get() = !overflowed && recordedBytes >= 4800

    /** Returns the data to send now, or null if it was kept for later. */
    fun append(data: ByteArray, ready: Boolean): ByteArray? {
        if (overflowed) return null
        recordedBytes += data.size
        if (ready) return data
        if (pendingBytes + data.size > 960_000) { overflowed = true; pending.clear(); return null }
        pending += data; pendingBytes += data.size
        return null
    }

    fun connected(): List<ByteArray> {
        val data = pending.toList()
        pending.clear(); pendingBytes = 0
        return if (overflowed) emptyList() else data
    }
}
