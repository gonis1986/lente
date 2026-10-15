package com.androbs.lens.ui

import android.graphics.Matrix
import kotlin.math.abs

/** Shared fit-center mapping between screenshot bitmap coordinates and view coordinates. */
object FitTransform {
    fun matrix(viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): Matrix {
        if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return Matrix()
        if (viewW == bmpW && viewH == bmpH) return Matrix()
        val viewAspect = viewW.toFloat() / viewH
        val bitmapAspect = bmpW.toFloat() / bmpH
        if (abs(viewAspect - bitmapAspect) < 0.001f) {
            return Matrix().apply { setScale(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH) }
        }
        val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val dx = (viewW - bmpW * scale) / 2f
        val dy = (viewH - bmpH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}
