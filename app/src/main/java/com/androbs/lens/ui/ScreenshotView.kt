package com.androbs.lens.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Draws the captured screen dimmed, fit-centered like the other layers. */
class ScreenshotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    private val dim = Paint().apply { color = 0x55000000 }

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        invalidate()
    }

    fun bitmap(): Bitmap? = bitmap

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val m = FitTransform.matrix(width, height, b.width, b.height)
        canvas.drawBitmap(b, m, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
    }
}
