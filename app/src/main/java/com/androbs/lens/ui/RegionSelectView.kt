package com.androbs.lens.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
            if (!v) sel.setEmpty()
            invalidate()
        }

    var onSelected: ((RectF) -> Unit)? = null

    private val sel = RectF()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4FC3F7.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val veil = Paint().apply { color = 0x66000000 }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectionEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                sel.set(downX, downY, downX, downY)
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    sel.set(
                        minOf(downX, event.x), minOf(downY, event.y),
                        maxOf(downX, event.x), maxOf(downY, event.y)
                    )
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val result = RectF(sel)
                    sel.setEmpty()
                    invalidate()
                    if (result.width() > 12 && result.height() > 12) {
                        onSelected?.invoke(result)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                sel.setEmpty()
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (sel.isEmpty) return
        canvas.drawRect(0f, 0f, width.toFloat(), sel.top, veil)
        canvas.drawRect(0f, sel.bottom, width.toFloat(), height.toFloat(), veil)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, veil)
        canvas.drawRect(sel.right, sel.top, width.toFloat(), sel.bottom, veil)
        canvas.drawRect(sel, stroke)
    }
}
