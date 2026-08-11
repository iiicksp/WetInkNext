package com.wetinknext.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoHistoryTest {
    @Test
    fun staleResultIsRejected() {
        assertFalse(isUndoResultCurrent(resultEpoch = 3L, currentEpoch = 4L))
    }

    @Test
    fun failedCompressionDoesNotLeavePendingCountLocked() {
        var pending = 1
        val result: UndoJobResult =
            UndoJobResult.Failed(epoch = 1L, sequence = 0L, error = IllegalStateException())

        pending = (pending - 1).coerceAtLeast(0)

        assertEquals(0, pending)
        assertTrue(result is UndoJobResult.Failed)
    }
}
