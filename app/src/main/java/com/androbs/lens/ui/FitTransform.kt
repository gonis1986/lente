package com.androbs.lens.ui

import android.graphics.Matrix

/** Shared fit-center mapping between screenshot bitmap coordinates and view coordinates. */
object FitTransform {
    fun matrix(viewW: Int, viewH: Int, bmpW: Int, bmpH: Int): Matrix {
        if (viewW <= 0 || viewH <= 0 || bmpW <= 0 || bmpH <= 0) return Matrix()
        val scale = minOf(viewW.toFloat() / bmpW, viewH.toFloat() / bmpH)
        val dx = (viewW - bmpW * scale) / 2f
        val dy = (viewH - bmpH * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }
}
