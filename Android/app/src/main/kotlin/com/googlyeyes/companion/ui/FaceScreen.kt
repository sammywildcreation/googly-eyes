package com.googlyeyes.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.googlyeyes.companion.AppGraph
import com.googlyeyes.companion.FaceCanvas
import com.googlyeyes.companion.LiveVoice
import com.googlyeyes.companion.MiniFace
import com.googlyeyes.companion.Palette
import com.googlyeyes.core.FocusSession
import com.googlyeyes.core.FocusStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** His face. Hold to talk, double-tap to wake him up or send him to sleep. */
@Composable
fun FaceScreen(g: AppGraph, palette: Palette, open: (Screen) -> Unit, scan: () -> Unit) {
    val prefs by g.prefs.prefs.collectAsState()
    val state by g.voice.state.collectAsState()
    val caption by g.voice.caption.collectAsState()
    val feedback by g.voice.feedback.collectAsState()
    val connected by g.link.connected.collectAsState()
    val paired by g.link.paired.collectAsState()
    val status by g.link.status.collectAsState()
    val focusSession by g.focus.current.collectAsState()
    val nudge by g.focus.nudge.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var holding by remember { mutableStateOf(false) }
    var answerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(caption) { if (caption.isNotEmpty()) answerDismissed = false }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FaceCanvas(
            g.animator, palette,
            Modifier.fillMaxSize().pointerInput(connected) {
                detectTapGestures(
                    onDoubleTap = { if (connected) g.voice.toggle() },
                    onPress = {
                        if (!connected) return@detectTapGestures
                        holdJob = scope.launch {
                            delay(300)  // a quick tap isn't a hold
                            if (!micGranted()) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return@launch }
                            holding = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            g.voice.beginAsk()
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        if (holding) {
                            holding = false
                            g.voice.endAsk()
                        }
                    },
                )
            },
        )

        ModeBorder(state)

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
            // Top bar: who he is and how he's connected, plus the buttons.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusDot(connected)
                    Spacer(Modifier.width(8.dp))
                    Text(prefs.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (prefs.silent) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.VisibilityOff, null, tint = palette.berry1, modifier = Modifier.height(16.dp))
                        Text(" Silent", color = palette.berry1, fontSize = 13.sp)
                    }
                    val session = focusSession
                    if (prefs.focusEnabled && session != null && session.status != FocusStatus.ENDED) {
                        Spacer(Modifier.width(10.dp))
                        FocusClock(g, session)
                    }
                }
                Spacer(Modifier.weight(1f))
                CornerButton(Icons.Filled.Keyboard, "Type a message") { open(Screen.CHAT) }
                if (prefs.focusEnabled) CornerButton(Icons.Filled.Timer, "Focus") { open(Screen.FOCUS) }
                CornerButton(Icons.Filled.History, "History") { open(Screen.HISTORY) }
                CornerButton(Icons.Filled.Settings, "Settings") { open(Screen.SETTINGS) }
            }

            // Silent mode: his answers show here instead of on the Mac.
            AnimatedVisibility(
                visible = prefs.silent && caption.isNotBlank() && !answerDismissed,
                enter = fadeIn(), exit = fadeOut(),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                AnswerCard(caption, prefs.name, palette) { answerDismissed = true }
            }

            if (nudge.isNotEmpty()) {
                Text(
                    nudge, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color(0xFFE5547A).copy(alpha = 0.85f)).padding(12.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // A hint at the bottom about what's happening.
            val hint = when {
                !paired -> ""
                !connected -> status
                holding -> "Listening… let go when you're done"
                state == LiveVoice.State.ASLEEP -> "Hold me to talk · tap the keyboard to type · double-tap to wake"
                state == LiveVoice.State.THINKING -> feedback.ifEmpty { "Thinking…" }
                state == LiveVoice.State.WAKING -> "Waking up…"
                else -> "Hold me to talk · double-tap to send me to sleep"
            }
            if (hint.isNotEmpty()) {
                Text(hint, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
            }
        }

        if (!paired) PairingOverlay(prefs.name, palette, scan)
    }
}

@Composable
private fun CornerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = 0.5f))
    }
}

@Composable
private fun FocusClock(g: AppGraph, session: FocusSession) {
    var remaining by remember { mutableDoubleStateOf(g.focus.remainingNow()) }
    LaunchedEffect(session) {
        while (true) { remaining = g.focus.remainingNow(); delay(250) }
    }
    Text(
        (if (session.status == FocusStatus.PAUSED) "Paused " else "") + FocusSession.clock(remaining),
        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AnswerCard(text: String, name: String, palette: Palette, close: () -> Unit) {
    Column(
        Modifier
            .padding(top = 10.dp)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xF01E1B29))
            .border(BorderStroke(2.dp, palette.berry2), RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = palette.berry1, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = close) { Icon(Icons.Filled.Close, "Close", tint = Palette.inkSoft) }
        }
        Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(end = 12.dp)) {
            SelectionContainer { Text(text, color = Color.White, fontSize = 17.sp, lineHeight = 24.sp) }
        }
    }
}

@Composable
private fun ModeBorder(state: LiveVoice.State) {
    val color = when (state) {
        LiveVoice.State.ASLEEP -> Color.Transparent
        LiveVoice.State.WAKING -> Color(0xFFFFD66B)
        LiveVoice.State.LISTENING, LiveVoice.State.ASKING -> Color(0xFF4F8BFF)
        LiveVoice.State.THINKING -> Color(0xFFC79BFF)
        LiveVoice.State.SPEAKING -> Color(0xFFFF9AD0)
    }
    val width = when (state) {
        LiveVoice.State.ASLEEP -> 0.dp
        LiveVoice.State.WAKING -> 4.dp
        LiveVoice.State.LISTENING -> 6.dp
        LiveVoice.State.ASKING -> 11.dp
        LiveVoice.State.THINKING, LiveVoice.State.SPEAKING -> 5.dp
    }
    val pulses = state == LiveVoice.State.WAKING || state == LiveVoice.State.ASKING || state == LiveVoice.State.THINKING
    val transition = rememberInfiniteTransition(label = "border")
    val wave by transition.animateFloat(0.65f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "pulse")
    if (width > 0.dp) {
        Box(
            Modifier.fillMaxSize().border(BorderStroke(width, color.copy(alpha = if (pulses) wave else 1f)), RoundedCornerShape(28.dp))
        )
    }
}

@Composable
private fun PairingOverlay(name: String, palette: Palette, scan: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 520.dp)) {
            MiniFace(palette, Modifier.width(120.dp).height(80.dp))
            Text("Pair $name with your Mac", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(
                "1. On your Mac, start the companion app.\n2. Click its icon in the menu bar and choose \"Pair Phone (QR)…\".\n3. Tap the button below and scan the code.\n\nYour phone and Mac need to be on the same Wi-Fi.",
                color = Palette.inkSoft, fontSize = 15.sp, lineHeight = 22.sp,
            )
            Button(onClick = scan, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Scan pairing code", fontSize = 17.sp) }
        }
    }
}
