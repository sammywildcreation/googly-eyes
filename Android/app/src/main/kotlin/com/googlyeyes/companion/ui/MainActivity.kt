package com.googlyeyes.companion.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.googlyeyes.companion.AppGraph
import com.googlyeyes.companion.CompanionApp
import com.googlyeyes.companion.Palette
import com.googlyeyes.core.PairingRecord
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // He sits under your monitor: keep the screen on while the app is open.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val graph = (application as CompanionApp).graph
        setContent { CompanionUi(graph) }
    }
}

enum class Screen { FACE, CHAT, HISTORY, CONVERSATION, SETTINGS, FOCUS }

@Composable
fun CompanionUi(g: AppGraph) {
    val prefs by g.prefs.prefs.collectAsState()
    val palette = remember(prefs.hue) { Palette(prefs.hue) }
    var screen by rememberSaveable { mutableStateOf(Screen.FACE) }
    var conversationID by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val record = PairingRecord.parse(text)
        if (record == null) {
            Toast.makeText(context, "That isn't a pairing code. On your Mac, use Pair Phone (QR) in the menu bar.", Toast.LENGTH_LONG).show()
        } else {
            g.link.pair(record)
            Toast.makeText(context, "Paired with ${record.service}", Toast.LENGTH_SHORT).show()
        }
    }
    val scan = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the pairing code on your Mac")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    BackHandler(enabled = screen != Screen.FACE) {
        screen = if (screen == Screen.CONVERSATION) Screen.HISTORY else Screen.FACE
    }

    MaterialTheme(colorScheme = colorScheme(palette)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (screen) {
                Screen.FACE -> FaceScreen(g, palette, open = { screen = it }, scan = scan)
                Screen.CHAT -> Box(Modifier.safeDrawingPadding()) { ChatScreen(g, palette) { screen = Screen.FACE } }
                Screen.HISTORY -> Box(Modifier.safeDrawingPadding()) {
                    HistoryScreen(g, open = { conversationID = it; screen = Screen.CONVERSATION }) { screen = Screen.FACE }
                }
                Screen.CONVERSATION -> Box(Modifier.safeDrawingPadding()) {
                    ConversationScreen(g, palette, conversationID) { screen = Screen.HISTORY }
                }
                Screen.SETTINGS -> Box(Modifier.safeDrawingPadding()) {
                    SettingsScreen(g, palette, scan = scan, openFocus = { screen = Screen.FOCUS }) { screen = Screen.FACE }
                }
                Screen.FOCUS -> Box(Modifier.safeDrawingPadding()) { FocusScreen(g, palette) { screen = Screen.FACE } }
            }
        }
    }
}
