package com.googlyeyes.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the Mac and the phone agree on. These mirror the Swift types in Shared/ field for field,
 * so the JSON each side writes is exactly what the other side expects.
 */
object GooglyService {
    /** Bonjour / NSD service type the Mac advertises. */
    const val TYPE = "_googly._tcp"
    /** TXT record key holding the paired Mac's public fingerprint. */
    const val ID_KEY = "bluey-id"
}

/** JSON settings that match Swift's JSONEncoder/JSONDecoder: nil fields are left out, unknown keys ignored. */
val WireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

@Serializable
enum class Mood {
    @SerialName("listening") LISTENING,
    @SerialName("resting") RESTING,
    @SerialName("thinking") THINKING,
    @SerialName("talking") TALKING,
    @SerialName("pointing") POINTING,
    @SerialName("happy") HAPPY,
    @SerialName("sleepy") SLEEPY,
}

/** What the face should show right now. gazeX: -1 left … 1 right. gazeY: -1 up … 1 down. talk: 0…1. */
@Serializable
data class FaceState(
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val mood: Mood = Mood.LISTENING,
    val talk: Double = 0.0,
)

/** One message between the Mac and the phone. Only the fields that matter for a message are set. */
@Serializable
data class Packet(
    val face: FaceState? = null,
    val hello: String? = null,
    val volume: Double? = null,
    val command: String? = null,
    val audio: String? = null,
    val speech: Int? = null,
    val callID: String? = null,
    val tool: String? = null,
    val text: String? = null,
    val image: String? = null,
)

/** Name, colour, silent mode and focus mode. The Mac keeps the real copy; the phone mirrors it. */
@Serializable
data class CompanionPrefs(
    val name: String = "Bluey",
    /** Hue in degrees, or null for the original blueberry. */
    val hue: Double? = null,
    val silent: Boolean = false,
    val focusEnabled: Boolean = false,
)

// MARK: Focus mode (Mac-authoritative state, mirrored for display)

@Serializable
enum class FocusActivity {
    @SerialName("on_task") ON_TASK,
    @SerialName("off_task") OFF_TASK,
    @SerialName("uncertain") UNCERTAIN,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("paused") PAUSED,
}

@Serializable
data class FocusCheck(
    val id: String = "",
    val elapsed: Double = 0.0,
    val activity: FocusActivity = FocusActivity.UNAVAILABLE,
    val probability: Double = 0.0,
    val confidence: Double = 0.0,
    val probabilities: Map<String, Double>? = null,
    val sampleDuration: Double? = null,
    val nudged: Boolean = false,
)

@Serializable
enum class FocusStatus {
    @SerialName("running") RUNNING,
    @SerialName("paused") PAUSED,
    @SerialName("ended") ENDED,
}

@Serializable
data class FocusSession(
    val id: String = "",
    val task: String = "",
    val duration: Double = 1800.0,
    /** Seconds since 1 Jan 2001 (Swift's default Date encoding). */
    val startedAt: Double = 0.0,
    val elapsed: Double = 0.0,
    val status: FocusStatus = FocusStatus.RUNNING,
    val detail: String = "",
    val checks: List<FocusCheck> = emptyList(),
    val developerMode: Boolean? = null,
    val usedDeveloperMode: Boolean? = null,
    val realElapsed: Double? = null,
) {
    val remaining: Double get() = maxOf(0.0, duration - elapsed)
    val startedAtMillis: Long get() = ((startedAt + SWIFT_EPOCH_OFFSET) * 1000).toLong()

    /** Share of checked time judged on task, 0…1, or null when nothing was checked. */
    val onTaskShare: Double?
        get() {
            val judged = checks.filter { it.activity == FocusActivity.ON_TASK || it.activity == FocusActivity.OFF_TASK }
            if (judged.isEmpty()) return null
            return judged.count { it.activity == FocusActivity.ON_TASK }.toDouble() / judged.size
        }

    companion object {
        /** Seconds between 1970 and 2001, the start of Swift's reference date. */
        const val SWIFT_EPOCH_OFFSET = 978_307_200.0

        fun clock(seconds: Double): String {
            val n = maxOf(0, kotlin.math.ceil(seconds).toInt())
            return "%02d:%02d".format(n / 60, n % 60)
        }
    }
}

@Serializable
data class FocusSync(
    val current: FocusSession? = null,
    val history: List<FocusSession> = emptyList(),
    val revision: Int = 0,
    val sentAt: Double = 0.0,
    val developerMode: Boolean? = null,
)
