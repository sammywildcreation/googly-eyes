package com.googlyeyes.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.googlyeyes.companion.AppGraph
import com.googlyeyes.companion.MiniFace
import com.googlyeyes.companion.Palette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(g: AppGraph, palette: Palette, scan: () -> Unit, openFocus: () -> Unit, onBack: () -> Unit) {
    val prefs by g.prefs.prefs.collectAsState()
    val connected by g.link.connected.collectAsState()
    val paired by g.link.paired.collectAsState()
    val status by g.link.status.collectAsState()
    var name by remember { mutableStateOf(prefs.name) }
    LaunchedEffect(prefs.name) { name = prefs.name }
    var volume by remember { mutableFloatStateOf(g.prefs.volume) }
    var customHue by remember { mutableFloatStateOf((prefs.hue ?: 228.0).toFloat()) }
    var confirmForget by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp)
                .widthIn(max = 700.dp)
        ) {
            // Who he is.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniFace(palette, Modifier.width(96.dp).height(64.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(prefs.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connected)
                        Spacer(Modifier.width(6.dp))
                        Text(status, fontSize = 13.sp, color = Palette.inkSoft)
                    }
                }
            }
            if (!connected) {
                Text("Name, colour and modes are saved on your Mac, so connect to change them.",
                    fontSize = 13.sp, color = Color(0xFFFFC27A), modifier = Modifier.padding(top = 10.dp))
            }

            SectionLabel("Name")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it.take(24) }, singleLine = true, modifier = Modifier.weight(1f), enabled = connected)
                Spacer(Modifier.width(10.dp))
                Button(onClick = { g.actions.setName(name) }, enabled = connected && name.trim().isNotEmpty() && name.trim() != prefs.name) { Text("Save") }
            }

            SectionLabel("Colour")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Palette.presets.forEach { (label, hue) ->
                    val selected = prefs.hue == hue
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(Palette.swatch(hue))
                                .border(BorderStroke(if (selected) 3.dp else 0.dp, Color.White), CircleShape)
                                .clickable(enabled = connected) { g.actions.setHue(hue) }
                        )
                        Text(label, fontSize = 11.sp, color = if (selected) Color.White else Palette.inkSoft)
                    }
                }
            }
            Text("Or pick any colour:", fontSize = 13.sp, color = Palette.inkSoft, modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.swatch(customHue.toDouble())))
                Spacer(Modifier.width(10.dp))
                Slider(
                    value = customHue, onValueChange = { customHue = it }, valueRange = 0f..359f,
                    onValueChangeFinished = { g.actions.setHue(customHue.toDouble()) }, enabled = connected,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionLabel("Modes")
            ToggleRow(
                "Silent mode",
                "Nothing appears on your Mac: no cursor, bubbles or cards, and no sounds. ${prefs.name} still looks at your screen and does what you ask; the answers show only on this phone.",
                prefs.silent, connected,
            ) { g.actions.setSilent(it) }
            ToggleRow(
                "Focus mode",
                "A focus timer that checks your screen every 30 seconds and nudges you if you drift. It uses more of your OpenAI credit while a session runs. Off by default.",
                prefs.focusEnabled, connected,
            ) { g.actions.setFocusEnabled(it) }
            if (prefs.focusEnabled) {
                OutlinedButton(onClick = openFocus, modifier = Modifier.padding(top = 4.dp)) { Text("Open focus sessions") }
            }

            SectionLabel("Sound")
            Text("Chirp volume (silent mode turns chirps off)", fontSize = 14.sp, color = Color.White)
            Slider(value = volume, onValueChange = { volume = it; g.prefs.volume = it })

            SectionLabel("Your Mac")
            Text(if (paired) "Paired. ${status}" else "Not paired yet.", fontSize = 14.sp, color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = scan) { Text(if (paired) "Pair again (scan QR)" else "Scan pairing code") }
                if (paired) OutlinedButton(onClick = { confirmForget = true }) { Text("Forget this Mac") }
            }

            SectionLabel("How to use")
            Text(
                "• Hold his face (or the mic button in Chat) while you talk, then let go.\n" +
                    "• Or tap the keyboard button and type.\n" +
                    "• Double-tap his face to wake him up or send him to sleep.\n" +
                    "• Ask about anything on your screen: \"what's this error?\", \"summarise this\".\n" +
                    "• Ask him to do things: \"open Notes\", \"reply yes to this email\". He asks before sending, posting or deleting anything.\n" +
                    "• Press Control-Option-S on the Mac to stop him at any time.",
                fontSize = 14.sp, color = Palette.inkSoft, lineHeight = 21.sp,
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this Mac?") },
            text = { Text("You'll need to scan the pairing code again to reconnect.") },
            confirmButton = { TextButton(onClick = { g.link.forget(); confirmForget = false }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Palette.panel).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(detail, fontSize = 13.sp, color = Palette.inkSoft, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
