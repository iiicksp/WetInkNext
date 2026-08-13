package com.wetinknext.engine.brush

import org.junit.Assert.assertEquals
import org.junit.Test

class DabBufferUploadTest {
    @Test
    fun uploadRangePositionsOnlyTheRequestedPendingDabs() {
        val buffer = DabBuffer(capacity = 4)
        repeat(3) { index ->
            buffer.add(index.toFloat(), 0f, 1f, 0f, 1f, 1f, 1f)
        }

        buffer.prepareForUpload(firstDab = 1, dabCount = 2)

        assertEquals(DabBuffer.FLOATS_PER_DAB, buffer.floats.position())
        assertEquals(DabBuffer.FLOATS_PER_DAB * 3, buffer.floats.limit())
        buffer.finishUpload()
    }
}
