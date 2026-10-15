package com.androbs.lens.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Drag-to-select rectangle layer; reports the selection in view coordinates. */
class RegionSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var selectionEnabled = false
        set(v) {
            field = v
            if (!v) {
                sel.setEmpty()
                trail.reset()
            }
            invalidate()
        }

    var onSelected: ((RectF) -> Unit)? = null

    /** Called on a tap (touch released without dragging). Coordinates are view coords. */
    var onTap: ((Float, Float) -> Unit)? = null

    private val sel = RectF()
    private val trail = Path()
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 13f
        color = 0xAA38BDF8.toInt()
        maskFilter = BlurMaskFilter(15f, BlurMaskFilter.Blur.NORMAL)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                sel.set(downX, downY, downX, downY)
                trail.reset()
                trail.moveTo(downX, downY)
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val midX = (lastX + event.x) / 2f
                    val midY = (lastY + event.y) / 2f
                    trail.quadTo(lastX, lastY, midX, midY)
                    lastX = event.x
                    lastY = event.y
                    trail.computeBounds(sel, true)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    trail.lineTo(event.x, event.y)
                    trail.computeBounds(sel, true)
                    val result = RectF(sel)
                    if (result.width() > 12 || result.height() > 12) {
                        onSelected?.invoke(result)
                    } else {
                        onTap?.invoke(event.x, event.y)
                    }
                    postDelayed({
                        trail.reset()
                        invalidate()
                    }, 320)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                sel.setEmpty()
                trail.reset()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (trail.isEmpty) return
        canvas.drawPath(trail, glow)
        stroke.shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            0xFF38BDF8.toInt(),
            0xFF818CF8.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(trail, stroke)
    }
}
