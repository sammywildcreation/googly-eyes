package com.googlyeyes.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.googlyeyes.companion.AppGraph
import com.googlyeyes.companion.Palette
import com.googlyeyes.core.FocusSession
import com.googlyeyes.core.FocusStatus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Focus sessions: start one, watch the countdown, see how past ones went. Only when Focus mode is on. */
@Composable
fun FocusScreen(g: AppGraph, palette: Palette, onBack: () -> Unit) {
    val prefs by g.prefs.prefs.collectAsState()
    val connected by g.link.connected.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Focus", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
            if (!prefs.focusEnabled) {
                Text("Focus mode is off.", fontSize = 18.sp, color = Color.White)
                Text("Turn it on to get a timer that checks your screen every 30 seconds and nudges you when you drift.",
                    color = Palette.inkSoft, modifier = Modifier.padding(vertical = 8.dp))
                Button(onClick = { g.actions.setFocusEnabled(true) }, enabled = connected) { Text("Turn on Focus mode") }
            } else {
                FocusBody(g, palette)
            }
        }
    }
}

@Composable
private fun FocusBody(g: AppGraph, palette: Palette) {
    val prefs by g.prefs.prefs.collectAsState()
    val connected by g.link.connected.collectAsState()
    val current by g.focus.current.collectAsState()
    val history by g.focus.history.collectAsState()
    val developer by g.focus.developerMode.collectAsState()
    val message by g.focus.message.collectAsState()
    var task by rememberSaveable { mutableStateOf("") }
    var minutes by remember { mutableFloatStateOf(30f) }
    Column {
            val session = current
            if (session != null && session.status != FocusStatus.ENDED) {
                var remaining by remember { mutableDoubleStateOf(g.focus.remainingNow()) }
                LaunchedEffect(session) { while (true) { remaining = g.focus.remainingNow(); delay(250) } }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.panel).padding(18.dp)
                ) {
                    Text(session.task, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(FocusSession.clock(remaining), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(session.detail, fontSize = 13.sp, color = Palette.inkSoft)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
                        if (session.status == FocusStatus.RUNNING) {
                            OutlinedButton(onClick = { g.focus.command("focusPause") }) { Text("Pause") }
                        } else {
                            Button(onClick = { g.focus.command("focusResume") }) { Text("Resume") }
                        }
                        OutlinedButton(onClick = { g.focus.command("focusEnd") }) { Text("End") }
                    }
                }
            } else {
                SectionLabel("Start a session")
                OutlinedTextField(task, { task = it }, label = { Text("What are you working on?") }, modifier = Modifier.fillMaxWidth())
                Text("${minutes.toInt()} minutes", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                Slider(value = minutes, onValueChange = { minutes = it }, valueRange = 5f..120f, steps = 22)
                Button(
                    onClick = { g.focus.command("focusStart", task = task.trim(), minutes = minutes.toDouble().let { kotlin.math.round(it) }) },
                    enabled = connected && task.isNotBlank(),
                ) { Text("Start focusing") }
                Text("Tip: you can also hold ${prefs.name}'s face and say \"I'm editing my video for 30 minutes\".",
                    fontSize = 13.sp, color = Palette.inkSoft, modifier = Modifier.padding(top = 8.dp))
            }
            if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = palette.berry1, modifier = Modifier.padding(top = 10.dp))

            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Developer mode", color = Color.White)
                    Text("4× faster clock, checks every 10 seconds. For testing.", fontSize = 12.sp, color = Palette.inkSoft)
                }
                Switch(checked = developer, onCheckedChange = { g.focus.command("focusDeveloperMode", developerMode = it) }, enabled = connected)
            }

            if (history.isNotEmpty()) {
                SectionLabel("Past sessions")
                history.sortedByDescending { it.startedAt }.take(30).forEach { HistoryRow(it, palette) }
            }
        }
    }

private val format = SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault())

@Composable
private fun HistoryRow(s: FocusSession, palette: Palette) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Palette.panel).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.task, color = Color.White, maxLines = 1)
            Text("${format.format(Date(s.startedAtMillis))} · ${(s.elapsed / 60).toInt()} min", fontSize = 12.sp, color = Palette.inkSoft)
        }
        Spacer(Modifier.width(10.dp))
        val share = s.onTaskShare
        Text(if (share == null) "–" else "${(share * 100).toInt()}% on task", color = palette.berry1, fontSize = 13.sp)
    }
    Spacer(Modifier.height(2.dp))
}
