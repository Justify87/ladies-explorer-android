package de.justify87.ladiesexplorer.core

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounded best-effort diagnostics. Barriers are reliable and never share the rejection path.
 * Worker operations must not block an application hot path.
 */
internal class DiagnosticEventQueue(private val maxPending: Int = 128) {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    fun offer(work: () -> Unit): Boolean {
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            return false
        }
        executor.execute {
            try {
                work()
            } finally {
                pending.decrementAndGet()
            }
        }
        return true
    }

    fun barrier() {
        executor.submit(Runnable { }).get()
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
