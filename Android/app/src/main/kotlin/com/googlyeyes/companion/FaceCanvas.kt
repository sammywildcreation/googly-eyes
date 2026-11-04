package com.googlyeyes.companion

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.googlyeyes.core.Mood
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The face, redrawn every frame. Landscape: he fills the screen. Portrait: he peeks up from the bottom. */
@Composable
fun FaceCanvas(animator: FaceAnimator, palette: Palette, modifier: Modifier = Modifier) {
    var frameNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frameNanos = it } }
    Canvas(modifier) {
        val now = if (frameNanos == 0L) System.nanoTime() / 1e9 else frameNanos / 1e9
        val frame = animator.step(now)
        drawFace(frame, palette)
    }
}

private const val DESIGN_W = 844f
private const val DESIGN_H = 390f

/** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%`. */
private fun blobPath(r: Rect): Path {
    val w = r.width; val h = r.height
    val tl = Size(0.52f * w, 0.58f * h); val tr = Size(0.48f * w, 0.56f * h)
    val br = Size(0.46f * w, 0.44f * h); val bl = Size(0.54f * w, 0.42f * h)
    val k = 0.5523f
    return Path().apply {
        moveTo(r.left + tl.width, r.top)
        lineTo(r.right - tr.width, r.top)
        cubicTo(r.right - tr.width * (1 - k), r.top, r.right, r.top + tr.height * (1 - k), r.right, r.top + tr.height)
        lineTo(r.right, r.bottom - br.height)
        cubicTo(r.right, r.bottom - br.height * (1 - k), r.right - br.width * (1 - k), r.bottom, r.right - br.width, r.bottom)
        lineTo(r.left + bl.width, r.bottom)
        cubicTo(r.left + bl.width * (1 - k), r.bottom, r.left, r.bottom - bl.height * (1 - k), r.left, r.bottom - bl.height)
        lineTo(r.left, r.top + tl.height)
        cubicTo(r.left, r.top + tl.height * (1 - k), r.left + tl.width * (1 - k), r.top, r.left + tl.width, r.top)
        close()
    }
}

/** The little crown on top of his head (60×40 design box). */
private fun crownPath(r: Rect): Path {
    val pts = listOf(30 to 4, 36 to 16, 52 to 12, 40 to 24, 46 to 36, 30 to 28, 14 to 36, 20 to 24, 8 to 12, 24 to 16)
    return Path().apply {
        pts.forEachIndexed { i, (x, y) ->
            val px = r.left + x / 60f * r.width
            val py = r.top + y / 40f * r.height
            if (i == 0) moveTo(px, py) else lineTo(px, py)
        }
        close()
    }
}

private fun oval(cx: Float, cy: Float, w: Float, h: Float) = Path().apply { addOval(Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)) }
private fun rectPath(l: Float, t: Float, w: Float, h: Float) = Path().apply { addRect(Rect(l, t, l + w, t + h)) }

private fun skinBrush(body: Rect, palette: Palette) = Brush.linearGradient(
    *Palette.stops.zip(palette.gradient).toTypedArray(),
    start = Offset(body.left + 0.25f * body.width, body.top + 0.067f * body.height),
    end = Offset(body.left + 0.75f * body.width, body.top + 0.933f * body.height),
)

private fun shineBrush(body: Rect) = Brush.radialGradient(
    colors = listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
    center = Offset(body.left + 246, body.top + 126), radius = 243f,
)

private fun DrawScope.fillSkin(path: Path, body: Rect, palette: Palette) {
    drawPath(path, skinBrush(body, palette))
    drawPath(path, shineBrush(body))
}

private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawFace(f: FaceAnimator.Frame, palette: Palette) {
    drawRect(Color.Black)
    val w = size.width; val h = size.height
    // Landscape fills the screen like the iPhone app; portrait fits the width and sits at the bottom.
    val s = if (w >= h) max(w / DESIGN_W, h / DESIGN_H) else w / DESIGN_W
    val left = (w - DESIGN_W * s) / 2
    val top = h - DESIGN_H * s

    withTransform({
        translate(left, top)
        scale(s, s, pivot = Offset.Zero)
    }) {
        val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
        val body = Rect(12f, 44f + bounce, 12f + 820f, 44f + bounce + 700f)
        val pivot = Offset(body.center.x, body.bottom)
        val stretch = 1f + (f.hop / 900).toFloat().coerceIn(-0.03f, 0.03f)
        withTransform({
            rotateRad(f.lean.toFloat(), pivot)
            scale(2 - stretch, stretch, pivot)
        }) {
            drawBody(f, body, palette)
        }
    }
}

private fun DrawScope.drawBody(f: FaceAnimator.Frame, body: Rect, palette: Palette) {
    val blob = blobPath(body)
    drawPath(blob, palette.berry3)
    drawPath(blob, skinBrush(body, palette))
    clipPath(blob) {
        drawPath(blob, shineBrush(body))
        drawPath(oval(body.left + 213, body.top + 51, 46f, 22f), Color.White.copy(alpha = 0.35f))
    }

    val navy = palette.nose
    val ink = Palette.ink
    val browLift = f.browLift.toFloat()
    drawPath(crownPath(Rect(body.left + 368, body.top - 20 - browLift * 0.3f, body.left + 368 + 84, body.top - 20 - browLift * 0.3f + 56)), navy)

    val eyeY = body.top + 157
    val eyes = listOf(Offset(body.left + 280, eyeY), Offset(body.left + 540, eyeY))
    val squash = 1 - f.talk.toFloat() * 0.12f

    // Blushy cheeks, rosier when he's happy (a soft radial glow instead of a blur).
    eyes.forEachIndexed { i, c ->
        val side = if (i == 0) -1f else 1f
        val center = Offset(c.x + side * 62, c.y + 117)
        val alpha = (0.25f + 0.45f * f.blush.toFloat()).coerceIn(0f, 1f)
        scale(1f, 42f / 90f, pivot = center) {
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF3A6D8).copy(alpha = alpha), Color(0xFFF3A6D8).copy(alpha = 0f)), center, 58f),
                radius = 58f, center = center,
            )
        }
    }

    eyes.forEachIndexed { i, c ->
        val side = if (i == 0) -1f else 1f

        // Little arched eyebrows do a lot of the acting.
        val browY = c.y - 112 - browLift.coerceIn(-10f, 18f) * 0.7f
        val tilt = f.browTilt.toFloat() * side * -1 + (if (f.mood == Mood.THINKING && i == 1) -0.25f else 0f)
        translate(c.x + side * 6, browY) {
            rotateRad(tilt, Offset.Zero) {
                val arch = Path().apply { moveTo(-38f, 6f); quadraticTo(0f, -12f, 38f, 6f) }
                drawPath(arch, navy, style = Stroke(width = 14f, cap = StrokeCap.Round))
            }
        }

        when {
            f.mood == Mood.RESTING -> {
                val shut = Path().apply { moveTo(c.x - 62, c.y + 6); quadraticTo(c.x, c.y + 52, c.x + 62, c.y + 6) }
                drawPath(shut, ink, style = Stroke(width = 18f, cap = StrokeCap.Round))
            }
            f.mood == Mood.HAPPY && f.squint > 0.6 -> {
                val arc = Path().apply { moveTo(c.x - 60, c.y + 34); quadraticTo(c.x, c.y - 54, c.x + 60, c.y + 34) }
                drawPath(arc, ink, style = Stroke(width = 24f, cap = StrokeCap.Round))
            }
            else -> drawEye(f, c, side, squash, body, palette)
        }
    }

    if (f.mood == Mood.RESTING) {
        // Little z's floating up while he naps.
        drawIntoCanvas { canvas ->
            for (i in 0 until 3) {
                val phase = ((f.time * 0.35 + i / 3.0) % 1.0)
                zPaint.textSize = 26f + i * 8f
                zPaint.color = Color.White.copy(alpha = (0.9 * sin(PI * phase)).toFloat().coerceIn(0f, 1f)).toArgb()
                val x = body.left + 660 + phase.toFloat() * 70 + sin(phase * 6).toFloat() * 8
                val y = body.top + 90 - phase.toFloat() * 110
                canvas.nativeCanvas.drawText("z", x, y, zPaint)
            }
        }
    }

    if (f.mood == Mood.THINKING) {
        for (i in 0 until 3) {
            val wobble = sin(f.time * 3 + i).toFloat() * 4
            drawCircle(Color.White.copy(alpha = 1f - i * 0.3f), radius = 8f,
                center = Offset(body.left + 698 + i * 26f, body.top + 48 - i * 12f + wobble))
        }
    }

    // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
    val talk = f.talk.toFloat()
    val noseW = 60f - talk * 8
    val noseH = 38f + talk * 34
    val nose = Rect(body.left + 410 - noseW / 2, body.top + 272 - talk * 6, body.left + 410 + noseW / 2, body.top + 272 - talk * 6 + noseH)
    if (f.mood == Mood.HAPPY && f.talk < 0.1) {
        val smile = Path().apply {
            moveTo(nose.left - 8, nose.top + 8)
            quadraticTo(nose.center.x, nose.top + 58, nose.right + 8, nose.top + 8)
            close()
        }
        drawPath(smile, navy)
    } else {
        drawOval(navy, topLeft = nose.topLeft, size = nose.size)
        if (f.talk > 0.25) {
            drawOval(Color(0xFFE58BC4).copy(alpha = 0.9f),
                topLeft = Offset(nose.center.x - noseW * 0.28f, nose.bottom - noseH * 0.38f),
                size = Size(noseW * 0.56f, noseH * 0.3f))
        }
    }
}

private fun DrawScope.drawEye(f: FaceAnimator.Frame, c: Offset, side: Float, squash: Float, body: Rect, palette: Palette) {
    val open = max(0.06f, 1 - f.closed.toFloat()) * squash
    val white = Rect(c.x - 95, c.y - 95 * open, c.x + 95, c.y + 95 * open)
    val whitePath = Path().apply { addOval(white) }
    drawPath(whitePath, Color.White)
    if (open <= 0.2f) return

    val (gx, gy) = FaceAnimator.clampGaze(f.gazeX, f.gazeY)
    val reach = 50f
    val r = 46f * f.pupil.toFloat()
    val px = c.x + gx.toFloat() * reach - side * 3
    var py = c.y + gy.toFloat() * reach * open
    if (f.mood == Mood.SLEEPY) py = max(py, c.y) + 28  // eyes sink under heavy lids
    clipPath(whitePath) {
        drawCircle(Palette.ink, radius = r, center = Offset(px, py))
        // Two catchlights make them shine.
        drawOval(Color.White, topLeft = Offset(px - r * 0.58f, py - r * 0.72f), size = Size(r * 0.56f, r * 0.56f))
        drawOval(Color.White.copy(alpha = 0.85f), topLeft = Offset(px + r * 0.28f, py + r * 0.22f), size = Size(r * 0.24f, r * 0.24f))
        // Happy cheeks push up from below as he smiles.
        if (f.squint > 0.02) {
            val sq = f.squint.toFloat()
            fillSkin(Path().apply { addOval(Rect(c.x - 110, c.y + 95 * open - 70 * sq, c.x + 110, c.y + 95 * open - 70 * sq + 160)) }, body, palette)
        }
        // Heavy, droopy lids when he's getting sleepy.
        if (f.mood == Mood.SLEEPY) {
            val droop = 0.5f + 0.06f * sin(f.time * 1.3).toFloat()
            fillSkin(rectPath(c.x - 100, white.top - 10, 200f, white.height * droop + 10), body, palette)
            drawLine(palette.nose.copy(alpha = 0.7f), Offset(c.x - 92, white.top + white.height * droop),
                Offset(c.x + 92, white.top + white.height * droop), strokeWidth = 8f, cap = StrokeCap.Round)
        }
        // A soft upper lid when he's focused on pointing.
        if (f.mood == Mood.POINTING) fillSkin(rectPath(c.x - 100, white.top - 20, 200f, 40f), body, palette)
    }
}

/** A small static face for headers and the color picker. */
@Composable
fun MiniFace(palette: Palette, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val frame = FaceAnimator.Frame(0.0, -0.2, Mood.HAPPY, 0.0, 0.0, 0.0, 0.0, 1.1, 10.0, 0.0, 0.0, 0.0, 0.5, 0.0)
        val s = min(size.width / 844f, size.height / 520f)
        withTransform({
            translate((size.width - 844f * s) / 2, size.height - 560f * s)
            scale(s, s, pivot = Offset.Zero)
        }) {
            drawBody(frame, Rect(12f, 44f, 832f, 560f), palette)
        }
    }
}
