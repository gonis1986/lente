package com.androbs.lens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaBalloonClustererTest {
    @Test
    fun dropsNarrowFuriganaColumn() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                vertical("漢字", 100f, 100f, 120f, 220f),
                vertical("ふりがな", 124f, 110f, 132f, 210f),
                vertical("続く", 100f, 230f, 120f, 320f)
            )
        )

        assertEquals(1, balloons.size)
        assertEquals("漢字続く", balloons.single().sourceText)
        assertEquals(2, balloons.single().lineRects.size)
        assertEquals(3, balloons.single().eraseRects.size)
    }

    @Test
    fun mergesVerticalColumnsAndReadsRightToLeft() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                vertical("左列", 130f, 100f, 150f, 200f),
                vertical("右列", 160f, 100f, 180f, 200f)
            )
        )

        assertEquals(1, balloons.size)
        assertEquals("右列左列", balloons.single().sourceText)
        assertTrue(balloons.single().isVertical)
    }

    @Test
    fun keepsSeparateVerticalBalloonsSeparate() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                vertical("一", 200f, 100f, 220f, 180f),
                vertical("二", 140f, 100f, 160f, 180f)
            )
        )

        assertEquals(2, balloons.size)
        assertEquals(listOf("一", "二"), balloons.map { it.sourceText })
    }

    @Test
    fun mergesHorizontalLatinLinesTopToBottomWithSpaces() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                OcrLine("second line", Box(20f, 118f, 120f, 130f)),
                OcrLine("first line", Box(20f, 100f, 120f, 112f))
            )
        )

        assertEquals(1, balloons.size)
        assertEquals("first line second line", balloons.single().sourceText)
        assertTrue(!balloons.single().isVertical)
    }

    @Test
    fun deduplicatesOverlappingElementsWithSameText() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                OcrLine(
                    "見",
                    Box(100f, 100f, 120f, 220f),
                    listOf(OcrElement("見", Box(100f, 110f, 120f, 130f)))
                ),
                OcrLine(
                    "見",
                    Box(103f, 100f, 123f, 220f),
                    listOf(OcrElement("見", Box(103f, 110f, 123f, 130f)))
                )
            )
        )

        assertEquals(1, balloons.size)
        assertEquals("見", balloons.single().sourceText)
    }

    @Test
    fun removesDuplicateCharacterAtVerticalColumnJoin() {
        val balloons = MangaBalloonClusterer.cluster(
            listOf(
                vertical("本当に大切なものを見", 160f, 100f, 180f, 220f),
                vertical("見つけられるかな。", 130f, 100f, 150f, 220f)
            )
        )

        assertEquals(1, balloons.size)
        assertEquals("本当に大切なものを見つけられるかな。", balloons.single().sourceText)
    }

    @Test
    fun rejectsClustersWithinSixPixelsOfCaptureEdges() {
        assertTrue(MangaBalloonClusterer.isNearCaptureEdge(Box(6f, 20f, 30f, 80f), 200f, 300f))
        assertTrue(MangaBalloonClusterer.isNearCaptureEdge(Box(20f, 20f, 194f, 80f), 200f, 300f))
        assertTrue(MangaBalloonClusterer.isNearCaptureEdge(Box(20f, 294f, 40f, 299f), 200f, 300f))
    }

    @Test
    fun keepsClustersMoreThanSixPixelsFromCaptureEdges() {
        assertTrue(!MangaBalloonClusterer.isNearCaptureEdge(Box(7f, 7f, 193f, 293f), 200f, 300f))
    }

    private fun vertical(text: String, l: Float, t: Float, r: Float, b: Float) =
        OcrLine(text, Box(l, t, r, b))
}
