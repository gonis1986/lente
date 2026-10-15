package com.androbs.lens.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Draws high-contrast, large, fully readable translation cards directly over text regions.
 * Expands width horizontally so Italian words are never squeezed into tiny unreadable columns.
 */
class TranslationLayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val rect: RectF, val text: String, val bg: Int, val fg: Int)

    private var boxes: List<Box> = emptyList()
    private var highlights: List<RectF> = emptyList()
    private var selection: List<RectF> = emptyList()
    private var srcW = 0
    private var srcH = 0

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#4B5563")
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x2E38BDF8
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x5938BDF8
    }
    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF38BDF8.toInt()
    }

    fun setContent(sourceWidth: Int, sourceHeight: Int, newBoxes: List<Box>) {
        srcW = sourceWidth
        srcH = sourceHeight
        boxes = newBoxes
        invalidate()
    }

    /** Faint pills over every detected text region (source coordinates). */
    fun setHighlights(sourceWidth: Int, sourceHeight: Int, rects: List<RectF>) {
        srcW = sourceWidth
        srcH = sourceHeight
        highlights = rects
        invalidate()
    }

    /** Strong pills over the currently selected regions (source coordinates). */
    fun setSelection(rects: List<RectF>) {
        selection = rects
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        invalidate()
    }

    fun clearAll() {
        boxes = emptyList()
        highlights = emptyList()
        selection = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (srcW <= 0 || srcH <= 0) return
        val m = FitTransform.matrix(width, height, srcW, srcH)
        val dst = RectF()

        for (r in highlights) {
            m.mapRect(dst, r)
            dst.inset(-4f, -2f)
            canvas.drawRoundRect(dst, 10f, 10f, highlightPaint)
        }
        for (r in selection) {
            m.mapRect(dst, r)
            dst.inset(-6f, -4f)
            canvas.drawRoundRect(dst, 12f, 12f, selectionFillPaint)
            canvas.drawRoundRect(dst, 12f, 12f, selectionStrokePaint)
        }

        for (box in boxes) {
            m.mapRect(dst, box.rect)

            // Make sure width is at least 320px (or screen width - margin) for readable Italian words
            val minCardWidth = min(width * 0.85f, max(dst.width(), 340f))
            val cx = dst.centerX()
            var left = cx - minCardWidth / 2f
            var right = cx + minCardWidth / 2f

            // Clamp inside screen bounds
            if (left < 16f) {
                right += (16f - left)
                left = 16f
            }
            if (right > width - 16f) {
                left -= (right - (width - 16f))
                right = width - 16f
            }
            if (left < 16f) left = 16f

            val cardRect = RectF(left, dst.top, right, dst.bottom)
            drawReadableCard(canvas, cardRect, box.text, box.bg, box.fg)
        }
    }

    private fun drawReadableCard(canvas: Canvas, rect: RectF, text: String, bg: Int, fg: Int) {
        val hPad = 14f
        val vPad = 10f
        val availW = (rect.width() - hPad * 2).toInt()
        if (availW <= 30) return

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }

        // Start with a large, comfortable font size (24sp equivalent down to 16sp minimum)
        var fontSize = 34f
        var layout: StaticLayout? = null

        while (fontSize >= 20f) {
            textPaint.textSize = fontSize
            val testLayout = StaticLayout.Builder
                .obtain(text, 0, text.length, textPaint, availW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .build()

            layout = testLayout
            if (testLayout.lineCount <= 4) {
                break
            }
            fontSize -= 2f
        }

        val finalLayout = layout ?: return
        val totalH = max(rect.height(), finalLayout.height + vPad * 2)
        val finalCardRect = RectF(rect.left, rect.top, rect.right, rect.top + totalH)

        // Draw solid dark rounded background with high contrast
        cardPaint.color = Color.parseColor("#EE111827")
        canvas.drawRoundRect(finalCardRect, 14f, 14f, cardPaint)
        canvas.drawRoundRect(finalCardRect, 14f, 14f, borderPaint)

        // Draw centered bold text
        canvas.save()
        val textY = finalCardRect.top + (finalCardRect.height() - finalLayout.height) / 2f
        canvas.translate(finalCardRect.left + hPad, textY)
        finalLayout.draw(canvas)
        canvas.restore()
    }

    companion object {
        fun estimateColors(bitmap: Bitmap, rect: Rect): Pair<Int, Int> {
            return Color.parseColor("#EE111827") to Color.WHITE
        }
    }
}
