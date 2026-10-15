package com.androbs.lens.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.androbs.lens.ocr.OcrTranslator
import kotlin.math.roundToInt

class TranslationLayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(
        val rect: RectF,
        val text: String,
        val bg: Int,
        val fg: Int,
        val lineRects: List<RectF> = listOf(rect),
        val halo: Int = bg,
        val isVertical: Boolean = false,
        val widenVertically: Boolean = false
    )

    private var boxes: List<Box> = emptyList()
    private var highlights: List<RectF> = emptyList()
    private var selection: List<RectF> = emptyList()
    private var srcW = 0
    private var srcH = 0
    private var srcOffsetX = 0f
    private var srcOffsetY = 0f
    private var shimmer: ValueAnimator? = null
    private var shimmerX = 0f

    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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
    private val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setContent(
        sourceWidth: Int,
        sourceHeight: Int,
        newBoxes: List<Box>,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        srcW = sourceWidth
        srcH = sourceHeight
        srcOffsetX = offsetX
        srcOffsetY = offsetY
        boxes = newBoxes
        invalidate()
    }

    fun setClusters(
        sourceWidth: Int,
        sourceHeight: Int,
        clusters: List<OcrTranslator.BalloonCluster>,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        setContent(
            sourceWidth,
            sourceHeight,
            clusters.map { cluster ->
                Box(
                    RectF(cluster.outerBounds),
                    cluster.translatedText,
                    cluster.bgColor,
                    cluster.textColor,
                    cluster.lineRects.map { RectF(it) },
                    cluster.strokeHaloColor,
                    cluster.isVertical,
                    cluster.widenVertically
                )
            },
            offsetX,
            offsetY
        )
    }

    fun setProcessing(processing: Boolean) {
        if (processing) {
            if (shimmer?.isRunning == true) return
            shimmer = ValueAnimator.ofFloat(-width.toFloat(), width * 2f).apply {
                duration = 950
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    shimmerX = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            shimmer?.cancel()
            shimmer = null
            invalidate()
        }
    }

    fun setHighlights(
        sourceWidth: Int,
        sourceHeight: Int,
        rects: List<RectF>,
        offsetX: Float = 0f,
        offsetY: Float = 0f
    ) {
        srcW = sourceWidth
        srcH = sourceHeight
        srcOffsetX = offsetX
        srcOffsetY = offsetY
        highlights = rects
        invalidate()
    }

    fun setSelection(rects: List<RectF>) {
        selection = rects
        invalidate()
    }

    fun clear() {
        boxes = emptyList()
        setProcessing(false)
        invalidate()
    }

    fun clearAll() {
        boxes = emptyList()
        highlights = emptyList()
        selection = emptyList()
        setProcessing(false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (srcW <= 0 || srcH <= 0) return
        val matrix = if (srcOffsetX != 0f || srcOffsetY != 0f) {
            val scale = minOf(width.toFloat() / srcW, height.toFloat() / srcH)
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(srcOffsetX, srcOffsetY)
            }
        } else {
            FitTransform.matrix(width, height, srcW, srcH)
        }
        val mapped = RectF()

        highlights.forEach { rect ->
            matrix.mapRect(mapped, rect)
            mapped.inset(-4f, -2f)
            canvas.drawRoundRect(mapped, 10f, 10f, highlightPaint)
        }
        selection.forEach { rect ->
            matrix.mapRect(mapped, rect)
            mapped.inset(-6f, -4f)
            canvas.drawRoundRect(mapped, 12f, 12f, selectionFillPaint)
            canvas.drawRoundRect(mapped, 12f, 12f, selectionStrokePaint)
        }

        boxes.forEach { box ->
            val lines = box.lineRects.map { line ->
                RectF().also { matrix.mapRect(it, line) }
            }
            drawInpainting(canvas, lines, box.bg, box.isVertical)
            matrix.mapRect(mapped, box.rect)
            val target = RectF(mapped)
            if (box.isVertical && box.widenVertically) {
                val extra = target.width() * 0.2f
                target.left = (target.left - extra * 0.5f).coerceAtLeast(8f)
                target.right = (target.right + extra * 0.5f).coerceAtMost(width - 8f)
            }
            target.inset(dp(2f), dp(2f))
            drawAdaptiveText(canvas, target, box.text, box.fg, box.halo)
        }
        drawShimmer(canvas)
    }

    private fun drawInpainting(canvas: Canvas, lines: List<RectF>, color: Int, vertical: Boolean) {
        val expansion = dp(3.5f)
        val patches = lines.map { RectF(it).apply { inset(-expansion, -expansion) } }.toMutableList()
        if (vertical) {
            for (i in lines.indices) {
                for (j in i + 1 until lines.size) {
                    val first = lines[i]
                    val second = lines[j]
                    val overlapTop = maxOf(first.top, second.top)
                    val overlapBottom = minOf(first.bottom, second.bottom)
                    val gapLeft = minOf(first.right, second.right)
                    val gapRight = maxOf(first.left, second.left)
                    if (overlapBottom > overlapTop && gapRight > gapLeft && gapRight - gapLeft < dp(72f)) {
                        patches += RectF(gapLeft, overlapTop, gapRight, overlapBottom)
                    }
                }
            }
        }
        patchPaint.color = color
        patchPaint.maskFilter = BlurMaskFilter(dp(3.5f), BlurMaskFilter.Blur.NORMAL)
        patches.forEach { canvas.drawRoundRect(it, dp(8f), dp(8f), patchPaint) }
        patchPaint.maskFilter = null
        patches.forEach { canvas.drawRoundRect(it, dp(8f), dp(8f), patchPaint) }
    }

    private fun drawAdaptiveText(canvas: Canvas, rect: RectF, text: String, color: Int, halo: Int) {
        val availableWidth = rect.width().roundToInt()
        if (availableWidth <= 1 || rect.height() <= 1f || text.isBlank()) return
        var low = 8.5f * resources.displayMetrics.scaledDensity
        var high = 26f * resources.displayMetrics.scaledDensity
        var best: StaticLayout? = null
        repeat(14) {
            val size = (low + high) / 2f
            textPaint.textSize = size
            val layout = buildLayout(text, availableWidth)
            if (layout.height <= rect.height() && layout.getLineEnd(layout.lineCount - 1) >= text.length) {
                best = layout
                low = size
            } else {
                high = size
            }
        }
        if (best == null) {
            textPaint.textSize = 8.5f * resources.displayMetrics.scaledDensity
            best = buildLayout(text, availableWidth)
        }
        val layout = best ?: return
        canvas.save()
        canvas.translate(rect.left, rect.top + (rect.height() - layout.height) / 2f)
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = dp(3.5f)
        textPaint.strokeJoin = Paint.Join.ROUND
        textPaint.color = halo
        layout.draw(canvas)
        textPaint.style = Paint.Style.FILL
        textPaint.color = color
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildLayout(text: String, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()

    private fun drawShimmer(canvas: Canvas) {
        if (shimmer?.isRunning != true) return
        shimmerPaint.shader = LinearGradient(
            shimmerX,
            0f,
            shimmerX + width * 0.55f,
            height.toFloat(),
            intArrayOf(Color.TRANSPARENT, 0x2238BDF8, Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shimmerPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        shimmer?.cancel()
        shimmer = null
        super.onDetachedFromWindow()
    }
}
