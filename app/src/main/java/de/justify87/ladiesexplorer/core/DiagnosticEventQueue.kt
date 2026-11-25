package de.justify87.ladiesexplorer.core

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Bound diagnostic work on hot paths; reserve capacity for failures and account for loss. */
internal class DiagnosticEventQueue(private val maxPending: Int = 128) {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    private val dropped = AtomicLong(0)
    private val failed = AtomicLong(0)
    private val criticalReserve = 32

    @Synchronized fun offer(critical: Boolean = false, work: () -> Unit): Boolean {
        if (pending.incrementAndGet() > maxPending + (if (critical) criticalReserve else 0)) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            return false
        }
        return try {
            executor.execute {
                try {
                    work()
                } catch (_: Exception) {
                    failed.incrementAndGet()
                } finally {
                    pending.decrementAndGet()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            false
        }
    }

    fun barrier() {
        val fence = synchronized(this) { executor.submit(Runnable { }) }
        fence.get()
    }

    fun lossSummary(): Pair<Long, Long> = dropped.get() to failed.get()

    fun shutdown() {
        executor.shutdownNow()
    }
}
