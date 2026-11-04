package com.googlyeyes.companion.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.googlyeyes.companion.Entry
import com.googlyeyes.companion.EntryKind
import com.googlyeyes.companion.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun colorScheme(palette: Palette): ColorScheme = darkColorScheme(
    primary = palette.berry2,
    onPrimary = Color.White,
    secondary = palette.berry1,
    background = Color.Black,
    surface = Palette.panel,
    surfaceVariant = Color(0xFF2A2638),
    onSurface = Color.White,
    onSurfaceVariant = Palette.inkSoft,
)

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, color = Palette.inkSoft, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
}

@Composable
fun StatusDot(on: Boolean) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color(0xFF5BE49B) else Palette.inkSoft.copy(alpha = 0.5f)))
}

private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

/** One line of a conversation, as a chat bubble (or a report card). */
@Composable
fun EntryBubble(entry: Entry, name: String, palette: Palette) {
    when (entry.kind) {
        EntryKind.REPORT -> ReportCard(entry.text, palette)
        EntryKind.CODEX, EntryKind.NUDGE -> NoteLine(if (entry.kind == EntryKind.CODEX) "Codex" else "Focus", entry.text)
        else -> {
            val mine = entry.kind == EntryKind.ASKED || entry.kind == EntryKind.HEARD
            Bubble(
                text = entry.text,
                mine = mine,
                faint = entry.kind == EntryKind.HEARD,
                label = if (mine) null else name,
                time = timeFormat.format(Date(entry.time)),
                palette = palette,
            )
        }
    }
}

@Composable
fun Bubble(text: String, mine: Boolean, palette: Palette, faint: Boolean = false, label: String? = null, time: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (mine) palette.berry3.copy(alpha = if (faint) 0.35f else 0.9f) else Color(0xFF2A2638))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (label != null) Text(label, fontSize = 11.sp, color = palette.berry1, fontWeight = FontWeight.SemiBold)
            SelectionContainer { Text(text, fontSize = 16.sp, color = Color.White.copy(alpha = if (faint) 0.7f else 1f), lineHeight = 22.sp) }
            if (time != null) Text(time, fontSize = 10.sp, color = Palette.inkSoft, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
fun ReportCard(text: String, palette: Palette) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF231F31))
            .clickable { open = !open }
            .padding(14.dp)
            .animateContentSize()
    ) {
        Text("RESEARCH REPORT", fontSize = 11.sp, letterSpacing = 1.sp, color = palette.berry1)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(text, fontSize = 15.sp, color = Color.White, lineHeight = 21.sp, maxLines = if (open) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
        }
        Text(if (open) "Tap to fold" else "Tap to read it all", fontSize = 12.sp, color = Palette.inkSoft, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun NoteLine(label: String, text: String) {
    Text("$label: $text", fontSize = 13.sp, color = Palette.inkSoft, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}
