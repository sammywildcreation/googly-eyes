package com.googlyeyes.companion

import com.googlyeyes.core.FaceState
import com.googlyeyes.core.Mood
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, private val stiffness: Double = 200.0, private val damping: Double = 0.7) {
    var velocity = 0.0
    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

/**
 * Smooths what the Mac asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops.
 * A straight port of the iPhone app's FaceAnimator.
 */
class FaceAnimator {
    data class Frame(
        val gazeX: Double, val gazeY: Double,
        val mood: Mood,
        val talk: Double,
        val closed: Double,
        val breathe: Double,
        val time: Double,
        val pupil: Double,
        val browLift: Double,
        val browTilt: Double,
        val lean: Double,
        val hop: Double,
        val blush: Double,
        val squint: Double,
    )

    private var target = FaceState()
    private var lastPacket = -100.0
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood = Mood.LISTENING
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = 2.0
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderX = 0.0
    private var wanderY = -0.3
    private var jitterX = 0.0
    private var jitterY = 0.0
    private var nextJitter = 0.0
    private var nextHop = 6.0

    /** Mood set on the phone itself (waking up, thinking, talking). */
    @Volatile var localMood: Mood? = null
    /** Loudness of sound playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }
    /** True while he's awake. Asleep, his eyes stay locked on your mouse. */
    @Volatile var awake = false

    @Synchronized
    fun receive(face: FaceState, time: Double) {
        target = face
        lastPacket = time
    }

    private fun rnd(a: Double, b: Double) = a + Random.nextDouble() * (b - a)

    @Synchronized
    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20).coerceAtLeast(0.0)
        lastTime = now

        val live = now - lastPacket < 2.5
        val wanted = localMood ?: if (live) target.mood else Mood.LISTENING
        if (wanted != mood) {
            mood = wanted
            blinkStart = now
            if (mood != Mood.SLEEPY && mood != Mood.RESTING) {
                hop.velocity += 260
                pupil.velocity += 3
            }
        }

        var wantX: Double
        var wantY: Double
        if (live) {
            wantX = target.gazeX; wantY = target.gazeY
        } else {
            if (now > nextSaccade) {
                wanderX = rnd(-0.9, 0.9); wanderY = rnd(-0.9, 0.4)
                nextSaccade = now + rnd(0.6, 2.2)
            }
            wantX = wanderX; wantY = wanderY
        }
        if (mood == Mood.THINKING) { wantX = 0.6 + 0.08 * sin(now * 1.3); wantY = -0.85 }
        if (now > nextJitter) {
            val amount = if (!awake) 0.015 else when (mood) { Mood.TALKING -> 0.07; Mood.THINKING -> 0.05; else -> 0.02 }
            jitterX = rnd(-amount, amount); jitterY = rnd(-amount, amount)
            nextJitter = now + rnd(0.5, 1.4)
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        var wantTalk = if (live) target.talk else if (localMood == Mood.TALKING) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        var wantPupil = 1.0; var wantLift = 0.0; var wantTilt = 0.0; var wantBlush = 0.25; var wantSquint = 0.0
        when (mood) {
            Mood.LISTENING -> { wantPupil = 1.12; wantLift = 10.0 }
            Mood.TALKING -> { wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3) }
            Mood.POINTING -> { wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14 }
            Mood.THINKING -> { wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22 }
            Mood.HAPPY -> { wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0 }
            Mood.RESTING -> { wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15 }
            Mood.SLEEPY -> { wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15 }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.SLEEPY && mood != Mood.RESTING) hop.velocity += rnd(180.0, 320.0)
            nextHop = now + rnd(7.0, 14.0)
        }
        hop.step(0.0, dt)

        val drowsy = mood == Mood.SLEEPY
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && Random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) rnd(1.6, 3.0) else rnd(2.0, 5.0)
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * if (mood == Mood.RESTING) 1.1 else 1.8)
        return Frame(
            gazeX.value, gazeY.value, mood, talk, closed, breathe, now, pupil.value, browLift.value,
            browTilt.value, lean.value, hop.value, blush.value, squint.value.coerceIn(0.0, 1.0),
        )
    }

    companion object {
        fun clampGaze(x: Double, y: Double): Pair<Double, Double> {
            val len = hypot(x, y)
            return if (len > 1) (x / len) to (y / len) else x to y
        }
    }
}
