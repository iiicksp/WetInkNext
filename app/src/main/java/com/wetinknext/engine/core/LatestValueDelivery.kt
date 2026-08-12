package com.wetinknext.engine.core

import java.util.concurrent.atomic.AtomicReference

/**
 * Retains the newest cross-thread value until a consumer is ready.
 *
 * GLSurfaceView can create EGL before Compose has installed its listener. A
 * thumbnail is a replaceable snapshot, so retaining only the newest frame is
 * both sufficient and bounded.
 */
internal class LatestValueDelivery<T> {
    private val pending = AtomicReference<T?>(null)

    fun offer(value: T) {
        pending.set(value)
    }

    fun dispatchTo(listener: ((T) -> Unit)?) {
        val callback = listener ?: return
        pending.getAndSet(null)?.let(callback)
    }

    fun clear() {
        pending.set(null)
    }
}
