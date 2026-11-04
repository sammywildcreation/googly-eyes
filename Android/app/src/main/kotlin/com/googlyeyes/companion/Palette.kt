package com.googlyeyes.companion

import androidx.compose.ui.graphics.Color

/**
 * His colors, the same as Shared/Palette.swift on the Mac: the original blueberry shades, rotated to the
 * chosen hue so the gradient keeps its light-to-deep look.
 */
class Palette(val hue: Double?) {
    val berry1 = color(0xA9BCFF)
    val berry2 = color(0x6C86F5)
    val berry3 = color(0x4254D6)
    val berry4 = color(0x2B2F8F)
    val nose = color(0x1C1F66)
    val tint = color(0xEEF0FF)
    val gradient = listOf(berry1, berry2, berry3, berry4)

    private fun color(hex: Int) = Color(0xFF000000 or tinted(hex, hue).toLong())

    companion object {
        val ink = Color(0xFF17151F)
        val inkSoft = Color(0xFFB9B2CC)
        val panel = Color(0xFF1E1B29)
        val stops = listOf(0f, 0.34f, 0.68f, 1f)

        /** Ready-made colors. A null hue is the original blueberry. Same list as the Mac menu. */
        val presets: List<Pair<String, Double?>> = listOf(
            "Blue" to null, "Purple" to 268.0, "Pink" to 322.0, "Red" to 354.0, "Orange" to 24.0,
            "Yellow" to 46.0, "Green" to 138.0, "Teal" to 176.0,
        )

        private val originalHue = hsv(0x6C86F5)[0]

        fun tinted(hex: Int, hue: Double?): Int {
            if (hue == null) return hex
            val c = hsv(hex)
            var h = (c[0] + hue - originalHue) % 360.0
            if (h < 0) h += 360.0
            return fromHsv(h, c[1], c[2])
        }

        private fun hsv(hex: Int): DoubleArray {
            val r = ((hex shr 16) and 0xFF) / 255.0
            val g = ((hex shr 8) and 0xFF) / 255.0
            val b = (hex and 0xFF) / 255.0
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val d = mx - mn
            var h = 0.0
            if (d > 0) {
                h = when (mx) {
                    r -> 60 * (((g - b) / d) % 6)
                    g -> 60 * ((b - r) / d + 2)
                    else -> 60 * ((r - g) / d + 4)
                }
            }
            if (h < 0) h += 360.0
            return doubleArrayOf(h, if (mx == 0.0) 0.0 else d / mx, mx)
        }

        private fun fromHsv(h: Double, s: Double, v: Double): Int {
            val c = v * s
            val x = c * (1 - kotlin.math.abs((h / 60) % 2 - 1))
            val m = v - c
            val (r, g, b) = when {
                h < 60 -> Triple(c, x, 0.0)
                h < 120 -> Triple(x, c, 0.0)
                h < 180 -> Triple(0.0, c, x)
                h < 240 -> Triple(0.0, x, c)
                h < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun byte(n: Double) = ((n + m) * 255).let { kotlin.math.round(it).toInt().coerceIn(0, 255) }
            return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        }

        /** A color swatch for a hue (for the color picker). */
        fun swatch(hue: Double?): Color = Palette(hue).berry2
    }
}
