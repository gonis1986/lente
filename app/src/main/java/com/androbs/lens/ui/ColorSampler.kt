package com.androbs.lens.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.androbs.lens.ocr.OcrTranslator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ColorSampler {
    data class SampledColors(val background: Int, val text: Int, val halo: Int)

    fun apply(bitmap: Bitmap, cluster: OcrTranslator.BalloonCluster): SampledColors {
        val background = medianPerimeter(bitmap, cluster.lineRects) ?: Color.WHITE
        val luminance = (
            0.2126f * Color.red(background) +
                0.7152f * Color.green(background) +
                0.0722f * Color.blue(background)
            ) / 255f
        val text = if (luminance > 0.45f) Color.parseColor("#111827")
        else Color.parseColor("#F8FAFC")
        val colors = SampledColors(background, text, background)
        cluster.bgColor = colors.background
        cluster.textColor = colors.text
        cluster.strokeHaloColor = colors.halo
        cluster.widenVertically = cluster.isVertical &&
            cluster.outerBounds.width() < cluster.outerBounds.height() * 0.8f &&
            surroundingsMatch(bitmap, cluster.outerBounds, background)
        return colors
    }

    private fun medianPerimeter(bitmap: Bitmap, rects: List<RectF>): Int? {
        val red = ArrayList<Int>()
        val green = ArrayList<Int>()
        val blue = ArrayList<Int>()
        rects.forEach { rect ->
            val offset = 3f
            val left = (rect.left - offset).toInt().coerceIn(0, bitmap.width - 1)
            val right = (rect.right + offset).toInt().coerceIn(0, bitmap.width - 1)
            val top = (rect.top - offset).toInt().coerceIn(0, bitmap.height - 1)
            val bottom = (rect.bottom + offset).toInt().coerceIn(0, bitmap.height - 1)
            val step = max(1, (max(rect.width(), rect.height()) / 40f).toInt())
            var x = left
            while (x <= right) {
                add(bitmap.getPixel(x, top), red, green, blue)
                add(bitmap.getPixel(x, bottom), red, green, blue)
                x += step
            }
            var y = top
            while (y <= bottom) {
                add(bitmap.getPixel(left, y), red, green, blue)
                add(bitmap.getPixel(right, y), red, green, blue)
                y += step
            }
        }
        if (red.isEmpty()) return null
        return Color.rgb(median(red), median(green), median(blue))
    }

    private fun surroundingsMatch(bitmap: Bitmap, outer: RectF, background: Int): Boolean {
        val band = max(4f, outer.width() * 0.1f)
        val sampleRects = listOf(
            RectF(outer.left - band, outer.top, outer.left, outer.bottom),
            RectF(outer.right, outer.top, outer.right + band, outer.bottom)
        )
        var samples = 0
        var matching = 0
        sampleRects.forEach { rect ->
            val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
            val right = rect.right.toInt().coerceIn(0, bitmap.width - 1)
            val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
            val bottom = rect.bottom.toInt().coerceIn(0, bitmap.height - 1)
            if (right < left || bottom < top) return@forEach
            val step = max(1, ((bottom - top) / 16f).toInt())
            var y = top
            while (y <= bottom) {
                val color = bitmap.getPixel((left + right) / 2, y)
                samples++
                if (colorDistance(color, background) <= 24) matching++
                y += step
            }
        }
        return samples >= 4 && matching.toFloat() / samples >= 0.7f
    }

    private fun colorDistance(first: Int, second: Int): Int =
        abs(Color.red(first) - Color.red(second)) +
            abs(Color.green(first) - Color.green(second)) +
            abs(Color.blue(first) - Color.blue(second))

    private fun add(color: Int, red: MutableList<Int>, green: MutableList<Int>, blue: MutableList<Int>) {
        red += Color.red(color)
        green += Color.green(color)
        blue += Color.blue(color)
    }

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2
        else sorted[middle]
    }
}
