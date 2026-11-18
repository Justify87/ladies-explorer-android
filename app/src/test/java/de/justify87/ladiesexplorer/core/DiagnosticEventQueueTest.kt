package de.justify87.ladiesexplorer.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventQueueTest {
    @Test
    fun saturatedEventQueueNeverDiscardsTheResetBarrier() {
        val queue = DiagnosticEventQueue(maxPending = 2)
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val awaiting = Executors.newSingleThreadExecutor()
        try {
            assertTrue(queue.offer {
                entered.countDown()
                gate.await()
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(queue.offer { })
            assertFalse(queue.offer { error("This event must be rejected") })

            val barrier = awaiting.submit { queue.barrier() }
            gate.countDown()
            barrier.get(5, TimeUnit.SECONDS)
            assertTrue(queue.offer { })
            queue.barrier()
        } finally {
            gate.countDown()
            awaiting.shutdownNow()
            queue.shutdown()
        }
    }

    @Test
    fun successfulEventsRunInFifoOrder() {
        val queue = DiagnosticEventQueue(maxPending = 8)
        val values = mutableListOf<Int>()
        try {
            for (value in 0..5) {
                assertTrue(queue.offer { values.add(value) })
            }
            queue.barrier()
            assertTrue(values == (0..5).toList())
        } finally {
            queue.shutdown()
        }
    }
}
