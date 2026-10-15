package com.androbs.lens.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.androbs.lens.ocr.OcrTranslator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object ColorSampler {
    data class SampledColors(val background: Int, val text: Int, val halo: Int)

    fun apply(
        bitmap: Bitmap,
        cluster: OcrTranslator.BalloonCluster,
        density: Float = 1f
    ): SampledColors {
        val background = medianPerimeter(bitmap, cluster.eraseRects) ?: Color.WHITE
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
        cluster.writingRect = if (cluster.isVertical) {
            expandedWritingRect(bitmap, cluster.outerBounds, background, 6f * density)
        } else {
            RectF(cluster.outerBounds)
        }
        return colors
    }

    private fun expandedWritingRect(
        bitmap: Bitmap,
        bounds: RectF,
        background: Int,
        insetPx: Float
    ): RectF {
        val maxExtension = bounds.width().coerceAtLeast(1f)
        val rowSamples = samplePositions(bounds.top, bounds.bottom, bitmap.height)
        val columnSamples = samplePositions(bounds.left, bounds.right, bitmap.width)
        val left = rowSamples.minOfOrNull {
            scanHorizontal(bitmap, bounds.left, it, -1, maxExtension, background)
        } ?: 0f
        val right = rowSamples.minOfOrNull {
            scanHorizontal(bitmap, bounds.right, it, 1, maxExtension, background)
        } ?: 0f
        val top = columnSamples.minOfOrNull {
            scanVertical(bitmap, bounds.top, it, -1, maxExtension, background)
        } ?: 0f
        val bottom = columnSamples.minOfOrNull {
            scanVertical(bitmap, bounds.bottom, it, 1, maxExtension, background)
        } ?: 0f
        val expanded = RectF(
            (bounds.left - left).coerceAtLeast(0f),
            (bounds.top - top).coerceAtLeast(0f),
            (bounds.right + right).coerceAtMost(bitmap.width.toFloat()),
            (bounds.bottom + bottom).coerceAtMost(bitmap.height.toFloat())
        )
        val insetX = min(insetPx, max(0f, (expanded.width() - 1f) / 2f))
        val insetY = min(insetPx, max(0f, (expanded.height() - 1f) / 2f))
        expanded.inset(insetX, insetY)
        return expanded
    }

    private fun samplePositions(start: Float, end: Float, limit: Int): List<Int> =
        listOf(0.12f, 0.3f, 0.5f, 0.7f, 0.88f)
            .map { (start + (end - start) * it).toInt().coerceIn(0, limit - 1) }
            .distinct()

    private fun scanHorizontal(
        bitmap: Bitmap,
        boundary: Float,
        y: Int,
        direction: Int,
        maxExtension: Float,
        background: Int
    ): Float {
        val firstX = if (direction < 0) floor(boundary).toInt() - 1 else ceil(boundary).toInt()
        var distance = 0
        while (distance < maxExtension.toInt()) {
            val x = firstX + direction * distance
            if (x !in 0 until bitmap.width ||
                colorDistance(bitmap.getPixel(x, y), background) > COLOR_TOLERANCE
            ) break
            distance++
        }
        return distance.toFloat()
    }

    private fun scanVertical(
        bitmap: Bitmap,
        boundary: Float,
        x: Int,
        direction: Int,
        maxExtension: Float,
        background: Int
    ): Float {
        val firstY = if (direction < 0) floor(boundary).toInt() - 1 else ceil(boundary).toInt()
        var distance = 0
        while (distance < maxExtension.toInt()) {
            val y = firstY + direction * distance
            if (y !in 0 until bitmap.height ||
                colorDistance(bitmap.getPixel(x, y), background) > COLOR_TOLERANCE
            ) break
            distance++
        }
        return distance.toFloat()
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

    private const val COLOR_TOLERANCE = 28
}
