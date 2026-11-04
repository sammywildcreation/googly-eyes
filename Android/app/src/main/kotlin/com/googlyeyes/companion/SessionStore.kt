package com.googlyeyes.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.googlyeyes.core.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
enum class EntryKind { HEARD, ASKED, REPLY, REPORT, CODEX, NUDGE }

/** One line in a conversation: something you said or typed, his reply, a research report, a note. */
@Serializable
data class Entry(
    val id: String = UUID.randomUUID().toString(),
    val kind: EntryKind,
    val text: String,
    val time: Long,
    /** The Realtime conversation item it came from, so its words can be filled in when they arrive. */
    val itemID: String? = null,
)

/** Everything from one wake-to-sleep conversation. */
@Serializable
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val started: Long,
    val ended: Long? = null,
    val entries: List<Entry> = emptyList(),
) {
    val visible: List<Entry> get() = entries.filter { it.text.isNotBlank() }.sortedBy { it.time }

    val summary: String
        get() = (visible.firstOrNull { it.kind == EntryKind.ASKED } ?: visible.firstOrNull())?.text ?: "Nothing said yet"

    fun exportText(name: String): String {
        val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        val lines = mutableListOf("$name conversation, ${SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(started))}", "")
        for (e in visible) {
            val who = when (e.kind) {
                EntryKind.HEARD -> "You (overheard)"
                EntryKind.ASKED -> "You"
                EntryKind.REPLY -> name
                EntryKind.REPORT -> "Research"
                EntryKind.CODEX -> "Codex"
                EntryKind.NUDGE -> "Focus"
            }
            lines += "[${time.format(Date(e.time))}] $who: ${e.text}"
        }
        return lines.joinToString("\n")
    }
}

/** Keeps every conversation on the phone, as one JSON file in the app's private storage. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "conversations.json")
    private val main = Handler(Looper.getMainLooper())
    private val serializer = ListSerializer(Conversation.serializer())

    private val _sessions = MutableStateFlow<List<Conversation>>(emptyList())
    /** Newest first. */
    val sessions: StateFlow<List<Conversation>> = _sessions.asStateFlow()
    private val _currentID = MutableStateFlow<String?>(null)
    val currentID: StateFlow<String?> = _currentID.asStateFlow()

    init {
        val saved = runCatching { WireJson.decodeFromString(serializer, file.readText()) }.getOrNull() ?: emptyList()
        // A conversation left open by a crash is closed at its last line.
        _sessions.value = saved.map { if (it.ended == null) it.copy(ended = it.entries.lastOrNull()?.time ?: it.started) else it }
    }

    val current: Conversation? get() = _sessions.value.firstOrNull { it.id == _currentID.value }

    fun start() {
        end()
        val session = Conversation(started = System.currentTimeMillis())
        _sessions.value = listOf(session) + _sessions.value
        _currentID.value = session.id
        save()
    }

    fun end() {
        val id = _currentID.value ?: return
        _currentID.value = null
        val session = _sessions.value.firstOrNull { it.id == id } ?: return
        _sessions.value = if (session.visible.isEmpty()) {
            _sessions.value.filter { it.id != id }  // nothing said: don't keep it
        } else {
            _sessions.value.map { if (it.id == id) it.copy(ended = System.currentTimeMillis(), entries = it.visible) else it }
        }
        save()
    }

    /** Holds a spot for something you said, in order. The words arrive a moment later. */
    fun placeholder(itemID: String, asked: Boolean) = update { s ->
        if (s.entries.any { it.itemID == itemID }) s
        else s.copy(entries = s.entries + Entry(kind = if (asked) EntryKind.ASKED else EntryKind.HEARD, text = "", time = now(), itemID = itemID))
    }

    fun heard(itemID: String, text: String, asked: Boolean) = update { s ->
        val words = text.trim()
        val index = s.entries.indexOfFirst { it.itemID == itemID }
        if (index >= 0) {
            val e = s.entries[index]
            s.copy(entries = s.entries.toMutableList().also { it[index] = e.copy(text = words, kind = if (asked) EntryKind.ASKED else e.kind) })
        } else if (words.isNotEmpty()) {
            s.copy(entries = s.entries + Entry(kind = if (asked) EntryKind.ASKED else EntryKind.HEARD, text = words, time = now(), itemID = itemID))
        } else s
    }

    fun typed(text: String) = add(EntryKind.ASKED, text)
    fun reply(text: String) = add(EntryKind.REPLY, text)
    fun report(text: String) = add(EntryKind.REPORT, text)
    fun codex(text: String) = add(EntryKind.CODEX, text)
    fun nudge(text: String) = add(EntryKind.NUDGE, text)

    private fun add(kind: EntryKind, text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        update { it.copy(entries = it.entries + Entry(kind = kind, text = words, time = now())) }
    }

    /** Recent words from this conversation, so a fresh connection can pick up where the last one left off. */
    fun recentText(name: String, limit: Int = 6000): String? {
        val text = current?.visible?.filter { it.kind != EntryKind.REPORT }
            ?.joinToString("\n") { (if (it.kind == EntryKind.REPLY) "$name: " else "") + it.text }
        return text?.takeIf { it.isNotEmpty() }?.takeLast(limit)
    }

    fun delete(id: String) {
        if (id == _currentID.value) _currentID.value = null
        _sessions.value = _sessions.value.filter { it.id != id }
        save()
    }

    fun deleteAll() {
        _currentID.value = null
        _sessions.value = emptyList()
        save()
    }

    private fun update(change: (Conversation) -> Conversation) {
        val id = _currentID.value ?: return
        _sessions.value = _sessions.value.map { if (it.id == id) change(it) else it }
        scheduleSave()
    }

    private val saveWork = Runnable { save() }
    private fun scheduleSave() { main.removeCallbacks(saveWork); main.postDelayed(saveWork, 800) }

    private fun save() {
        main.removeCallbacks(saveWork)
        val text = WireJson.encodeToString(serializer, _sessions.value)
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    private fun now() = System.currentTimeMillis()
}
