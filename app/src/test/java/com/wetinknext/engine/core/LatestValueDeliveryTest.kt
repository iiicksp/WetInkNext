package com.wetinknext.engine.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestValueDeliveryTest {
    @Test
    fun captureBeforeListenerIsDeliveredAfterListenerIsInstalled() {
        val delivery = LatestValueDelivery<String>()
        val delivered = mutableListOf<String>()

        delivery.offer("first-thumbnail")
        delivery.dispatchTo(null)
        delivery.dispatchTo(delivered::add)

        assertEquals(listOf("first-thumbnail"), delivered)
    }

    @Test
    fun newestCaptureReplacesAnUndeliveredOlderCapture() {
        val delivery = LatestValueDelivery<String>()
        val delivered = mutableListOf<String>()

        delivery.offer("outdated")
        delivery.offer("current")
        delivery.dispatchTo(delivered::add)

        assertEquals(listOf("current"), delivered)
    }
}
