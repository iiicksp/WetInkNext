package com.wetinknext.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AutosaveCoordinatorTest {
    @Test
    fun laterCompletedEditReplacesEarlierPendingSave() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = AutosaveCoordinator(scope, debounceMillis = 30L)
        val saved = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)

        coordinator.schedule { saved += "first" }
        coordinator.schedule {
            saved += "second"
            latch.countDown()
        }

        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("second"), saved)
        scope.cancel()
    }
}
