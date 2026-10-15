package com.androbs.lens.ocr

import java.text.Normalizer
import kotlin.math.max

/** Geometry-only OCR input; intentionally independent of Android and ML Kit. */
data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width: Float get() = max(0f, r - l)
    val height: Float get() = max(0f, b - t)
    val centerX: Float get() = (l + r) / 2f
    val centerY: Float get() = (t + b) / 2f
}

data class OcrElement(val text: String, val box: Box)

data class OcrLine(
    val text: String,
    val box: Box,
    val elements: List<OcrElement> = emptyList()
)

data class ClusteredBalloon(
    val id: String,
    val outerBounds: Box,
    val lineRects: List<Box>,
    val sourceText: String,
    val isVertical: Boolean
)

/** Clusters OCR rows/columns into likely manga speech balloons. */
object MangaBalloonClusterer {
    private data class PreparedLine(
        val source: OcrLine,
        val text: String,
        val vertical: Boolean
    )

    fun cluster(lines: List<OcrLine>): List<ClusteredBalloon> {
        if (lines.isEmpty()) return emptyList()

        val candidates = lines.filter { it.text.isNotBlank() && it.box.width > 0f && it.box.height > 0f }
        if (candidates.isEmpty()) return emptyList()
        val verticalCandidates = candidates.filter { isVertical(it) }
        val medianColumnWidth = median(verticalCandidates.map { it.box.width })
        val furiganaLimit = medianColumnWidth * 0.45f

        val prepared = candidates.mapNotNull { line ->
            val vertical = isVertical(line)
            if (vertical && medianColumnWidth > 0f && line.box.width < furiganaLimit) {
                return@mapNotNull null
            }
            val text = if (vertical && line.elements.isNotEmpty()) {
                line.elements
                    .filter { medianColumnWidth <= 0f || it.box.width >= furiganaLimit }
                    .sortedWith(compareBy<OcrElement> { it.box.t }.thenBy { it.box.l })
                    .joinToString("") { normalize(it.text) }
                    .ifBlank { normalize(line.text) }
            } else {
                normalize(line.text)
            }
            if (text.isBlank()) null else PreparedLine(line, text, vertical)
        }
        if (prepared.isEmpty()) return emptyList()

        val avgCharSize = verticalCandidates.map {
            it.box.height / max(1, it.text.length).toFloat()
        }.average().toFloat().takeIf { it > 0f } ?: 1f
        val horizontalLines = prepared.filterNot { it.vertical }
        val avgLineHeight = horizontalLines.map { it.source.box.height }
            .average().toFloat().takeIf { it > 0f } ?: avgCharSize

        val union = UnionFind(prepared.size)
        for (i in prepared.indices) {
            for (j in i + 1 until prepared.size) {
                val first = prepared[i]
                val second = prepared[j]
                if (first.vertical != second.vertical) continue
                if (first.vertical) {
                    val xGap = intervalGap(
                        first.source.box.l, first.source.box.r,
                        second.source.box.l, second.source.box.r
                    )
                    val yGap = intervalGap(
                        first.source.box.t, first.source.box.b,
                        second.source.box.t, second.source.box.b
                    )
                    val verticalTouch = intervalsOverlap(
                        first.source.box.t, first.source.box.b,
                        second.source.box.t, second.source.box.b
                    ) || yGap < 0.8f * avgCharSize
                    if (verticalTouch && xGap < 1.65f * medianColumnWidth) {
                        union.join(i, j)
                    }
                } else {
                    val horizontalOverlap = intervalsOverlap(
                        first.source.box.l, first.source.box.r,
                        second.source.box.l, second.source.box.r
                    )
                    val yGap = intervalGap(
                        first.source.box.t, first.source.box.b,
                        second.source.box.t, second.source.box.b
                    )
                    if (horizontalOverlap && yGap < 1.5f * avgLineHeight) {
                        union.join(i, j)
                    }
                }
            }
        }

        return prepared.indices.groupBy { union.root(it) }.values.mapNotNull { indices ->
            val members = indices.map { prepared[it] }
            val vertical = members.first().vertical
            val ordered = if (vertical) orderVertical(members, medianColumnWidth)
            else members.sortedWith(
                compareBy<PreparedLine> { it.source.box.t }.thenBy { it.source.box.l }
            )
            val boxes = members.map { it.source.box }
            val outer = Box(
                boxes.minOf { it.l },
                boxes.minOf { it.t },
                boxes.maxOf { it.r },
                boxes.maxOf { it.b }
            )
            val text = if (vertical) {
                ordered.joinToString("") { it.text }
            } else {
                ordered.joinToString(" ") { it.text }
            }.trim()
            if (text.isBlank()) null else ClusteredBalloon(
                id = "balloon-${indices.minOrNull()}",
                outerBounds = outer,
                lineRects = ordered.map { it.source.box },
                sourceText = text,
                isVertical = vertical
            )
        }.sortedWith { first, second ->
            when {
                first.isVertical && second.isVertical ->
                    compareValuesBy(second, first, { it.outerBounds.centerX }, { -it.outerBounds.t })
                first.isVertical -> -1
                second.isVertical -> 1
                else -> compareValuesBy(first, second, { it.outerBounds.t }, { it.outerBounds.l })
            }
        }
    }

    private fun isVertical(line: OcrLine): Boolean =
        line.box.height > line.box.width * 1.18f && containsCjk(line.text)

    private fun containsCjk(text: String): Boolean = text.any { ch ->
        val code = ch.code
        code in 0x3040..0x30FF ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0xAC00..0xD7AF
    }

    private fun normalize(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return buildString(normalized.length) {
            normalized.forEach { ch ->
                append(
                    when (ch) {
                        '｡' -> '。'
                        '､' -> '、'
                        '，' -> '、'
                        '．' -> '。'
                        else -> ch
                    }
                )
            }
        }
    }

    private fun orderVertical(lines: List<PreparedLine>, medianWidth: Float): List<PreparedLine> {
        val columns = mutableListOf<MutableList<PreparedLine>>()
        lines.sortedByDescending { it.source.box.centerX }.forEach { line ->
            val column = columns.firstOrNull { existing ->
                val center = existing.map { it.source.box.centerX }.average().toFloat()
                kotlin.math.abs(center - line.source.box.centerX) <= max(medianWidth, 1f) * 0.5f
            }
            if (column == null) columns += mutableListOf(line) else column += line
        }
        return columns
            .sortedByDescending { column -> column.map { it.source.box.centerX }.average() }
            .flatMap { column -> column.sortedBy { it.source.box.t } }
    }

    private fun intervalsOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Boolean =
        minOf(a1, b1) > maxOf(a0, b0)

    private fun intervalGap(a0: Float, a1: Float, b0: Float, b1: Float): Float =
        when {
            a1 < b0 -> b0 - a1
            b1 < a0 -> a0 - b1
            else -> 0f
        }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    private class UnionFind(size: Int) {
        private val parents = IntArray(size) { it }

        fun root(index: Int): Int {
            if (parents[index] != index) parents[index] = root(parents[index])
            return parents[index]
        }

        fun join(first: Int, second: Int) {
            val a = root(first)
            val b = root(second)
            if (a != b) parents[b] = a
        }
    }
}
