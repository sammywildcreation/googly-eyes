package com.googlyeyes.companion

import android.content.Context
import com.googlyeyes.core.CompanionPrefs
import com.googlyeyes.core.PairingRecord
import com.googlyeyes.core.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings kept on the phone: a copy of the Mac's prefs (so his name and color show even before the Mac
 * connects), the pairing credential, and phone-only things like chirp volume.
 */
class PrefsRepository(context: Context) {
    private val store = context.getSharedPreferences("companion", Context.MODE_PRIVATE)

    private val _prefs = MutableStateFlow(
        store.getString("prefs", null)?.let { runCatching { WireJson.decodeFromString(CompanionPrefs.serializer(), it) }.getOrNull() }
            ?: CompanionPrefs()
    )
    /** Name, color, silent mode and focus mode, as last heard from the Mac (or changed here). */
    val prefs: StateFlow<CompanionPrefs> = _prefs.asStateFlow()

    fun update(prefs: CompanionPrefs) {
        _prefs.value = prefs
        store.edit().putString("prefs", WireJson.encodeToString(CompanionPrefs.serializer(), prefs)).apply()
    }

    /** The paired Mac. The secret inside is a credential: it stays in this app's private storage. */
    var pairing: PairingRecord?
        get() = store.getString("pairing", null)?.let { runCatching { WireJson.decodeFromString(PairingRecord.serializer(), it) }.getOrNull() }
        set(value) {
            store.edit().apply {
                if (value == null) remove("pairing") else putString("pairing", WireJson.encodeToString(PairingRecord.serializer(), value))
            }.apply()
        }

    var volume: Float
        get() = store.getFloat("volume", 0.8f)
        set(value) = store.edit().putFloat("volume", value.coerceIn(0f, 1f)).apply()
}
