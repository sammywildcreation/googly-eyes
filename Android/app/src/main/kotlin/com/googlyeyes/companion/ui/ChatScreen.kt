package com.googlyeyes.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.googlyeyes.companion.AppGraph
import com.googlyeyes.companion.Conversation
import com.googlyeyes.companion.EntryKind
import com.googlyeyes.companion.LiveVoice
import com.googlyeyes.companion.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The current conversation, with a box to type in. Works in both normal and silent mode. */
@Composable
fun ChatScreen(g: AppGraph, palette: Palette, onBack: () -> Unit) {
    val prefs by g.prefs.prefs.collectAsState()
    val sessions by g.store.sessions.collectAsState()
    val currentID by g.store.currentID.collectAsState()
    val state by g.voice.state.collectAsState()
    val caption by g.voice.caption.collectAsState()
    val feedback by g.voice.feedback.collectAsState()
    val connected by g.link.connected.collectAsState()
    val current = sessions.firstOrNull { it.id == currentID }
    val entries = current?.visible.orEmpty()
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var holding by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // While he's answering, his reply streams in before it's saved.
    val streaming = (state == LiveVoice.State.SPEAKING || state == LiveVoice.State.THINKING) &&
        caption.isNotBlank() && entries.lastOrNull()?.let { it.kind == EntryKind.REPLY && it.text == caption } != true
    val itemCount = entries.size + (if (streaming) 1 else 0)
    LaunchedEffect(itemCount, caption.length) { if (itemCount > 0) list.animateScrollToItem(itemCount - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Chat with ${prefs.name}", onBack) {
            FilterChip(
                selected = prefs.silent,
                onClick = { g.actions.setSilent(!prefs.silent) },
                label = { Text(if (prefs.silent) "Silent" else "Silent off") },
                enabled = connected,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            if (prefs.silent) "Silent mode: nothing shows on your Mac. ${prefs.name} looks at your screen and answers here."
            else "Answers show as a bubble on your Mac and are saved here.",
            fontSize = 12.sp, color = Palette.inkSoft, modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list) {
            if (entries.isEmpty() && !streaming) {
                item {
                    Text(
                        "Ask anything about your screen, like \"What does this error mean?\", \"Summarise this page\" or \"Reply yes to this email\".",
                        color = Palette.inkSoft, fontSize = 15.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp, start = 20.dp, end = 20.dp),
                    )
                }
            }
            items(entries, key = { it.id }) { EntryBubble(it, prefs.name, palette) }
            if (streaming) item { Bubble(caption, mine = false, palette = palette, label = prefs.name) }
        }

        if (state == LiveVoice.State.THINKING || holding) {
            Text(if (holding) "Listening… let go when you're done" else feedback.ifEmpty { "Thinking…" },
                fontSize = 13.sp, color = palette.berry1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (!connected) {
            Text("Not connected to your Mac yet.", fontSize = 13.sp, color = Color(0xFFFF9AA8), modifier = Modifier.padding(horizontal = 16.dp))
        }

        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message ${prefs.name}…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                enabled = connected,
            )
            Spacer(Modifier.size(8.dp))
            if (text.isBlank()) {
                // Hold to talk, like holding his face.
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(if (holding) palette.berry1 else palette.berry3)
                        .pointerInput(connected) {
                            detectTapGestures(onPress = {
                                if (!connected) return@detectTapGestures
                                holdJob = scope.launch {
                                    delay(150)
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO); return@launch
                                    }
                                    holding = true
                                    g.voice.beginAsk()
                                }
                                tryAwaitRelease()
                                holdJob?.cancel()
                                if (holding) { holding = false; g.voice.endAsk() }
                            })
                        },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Mic, "Hold to talk", tint = Color.White) }
            } else {
                IconButton(
                    onClick = { g.voice.sendText(text); text = "" },
                    enabled = connected,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(palette.berry2),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White) }
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())

/** Every saved conversation, newest first. */
@Composable
fun HistoryScreen(g: AppGraph, open: (String) -> Unit, onBack: () -> Unit) {
    val sessions by g.store.sessions.collectAsState()
    val currentID by g.store.currentID.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("History", onBack) {
            if (sessions.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, "Delete all") }
        }
        if (sessions.isEmpty()) {
            Text("No conversations yet. Talk to him or type a message and it shows up here.",
                color = Palette.inkSoft, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { s -> ConversationRow(s, live = s.id == currentID) { open(s.id) } }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all conversations?") },
            text = { Text("This removes every saved conversation from this phone.") },
            confirmButton = { TextButton(onClick = { g.store.deleteAll(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversationRow(s: Conversation, live: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.panel).clickable(onClick = onClick).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(dateFormat.format(Date(s.started)), fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            if (live) Text("LIVE", fontSize = 11.sp, color = Color(0xFF5BE49B))
            else Text("${s.visible.count { it.kind == EntryKind.ASKED }} asked", fontSize = 12.sp, color = Palette.inkSoft)
        }
        Spacer(Modifier.height(4.dp))
        Text(s.summary, color = Palette.inkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** One saved conversation, with share and delete. */
@Composable
fun ConversationScreen(g: AppGraph, palette: Palette, id: String?, onBack: () -> Unit) {
    val prefs by g.prefs.prefs.collectAsState()
    val sessions by g.store.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == id }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(session?.let { dateFormat.format(Date(it.started)) } ?: "Conversation", onBack) {
            if (session != null) {
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.exportText(prefs.name))
                    context.startActivity(Intent.createChooser(send, "Share conversation"))
                }) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { g.store.delete(session.id); onBack() }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
        if (session == null) {
            Text("This conversation was deleted.", color = Palette.inkSoft, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                items(session.visible, key = { it.id }) { EntryBubble(it, prefs.name, palette) }
            }
        }
    }
}
