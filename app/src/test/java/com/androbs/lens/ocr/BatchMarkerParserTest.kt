package com.androbs.lens.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchMarkerParserTest {
    @Test
    fun parsesMatchingNumberedMarkers() {
        assertEquals(
            listOf("ciao", "mondo"),
            BatchMarkerParser.parse("[[0]] ciao\n[[1]] mondo", 2)
        )
    }

    @Test
    fun returnsNullWhenMarkerCountDoesNotMatch() {
        assertNull(BatchMarkerParser.parse("[[0]] ciao", 2))
    }
}
