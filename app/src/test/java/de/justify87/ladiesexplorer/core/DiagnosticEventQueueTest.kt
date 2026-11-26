package de.justify87.ladiesexplorer.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticEventQueueTest {
    @Test(timeout = 10_000)
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
            val secondRan = CountDownLatch(1)
            assertTrue(queue.offer { secondRan.countDown() })
            val rejectedRan = CountDownLatch(1)
            val criticalRan = CountDownLatch(1)
            assertFalse(queue.offer { rejectedRan.countDown() })
            assertTrue(queue.offer(critical = true) { criticalRan.countDown() })
            assertEquals(1L to 0L, queue.lossSummary())

            val barrierStarted = CountDownLatch(1)
            val barrier = awaiting.submit {
                barrierStarted.countDown()
                queue.barrier()
                assertEquals(0L, secondRan.count)
                assertEquals(0L, criticalRan.count)
                assertEquals(1L, rejectedRan.count)
            }
            assertTrue(barrierStarted.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { barrier.get(250, TimeUnit.MILLISECONDS) }
            gate.countDown()
            barrier.get(5, TimeUnit.SECONDS)
            assertEquals(1L to 0L, queue.lossSummary())
            assertTrue(queue.offer { })
            queue.barrier()
        } finally {
            gate.countDown()
            awaiting.shutdownNow()
            queue.shutdown()
        }
    }

    @Test(timeout = 10_000)
    fun bridgeFailureIsCountedWithoutBlockingLaterEvents() {
        val queue = DiagnosticEventQueue()
        try {
            assertTrue(queue.offer { error("synthetic bridge failure") })
            val delivered = CountDownLatch(1)
            assertTrue(queue.offer(critical = true) { delivered.countDown() })
            queue.barrier()
            assertEquals(0L, delivered.count)
            assertEquals(0L to 1L, queue.lossSummary())
        } finally {
            queue.shutdown()
        }
    }

    @Test fun mediaFailureDetailsAreBoundedAndMaskSourcePaths() {
        val failure = IllegalStateException("decode failed https://example.invalid/private /data/private", IllegalArgumentException("bad image"))
        val result = diagnosticFailure(failure, includeMessage = true)
        assertTrue(result.contains("IllegalStateException>IllegalArgumentException"))
        assertTrue(result.contains("reason=decode,other"))
        assertFalse(result.contains("example.invalid"))
        assertFalse(result.contains("/data/private"))
    }

    @Test fun arbitraryExternalTextNeverSurvivesReasonClassification() {
        val text = "decode failed /data/private name.jpg relative/media.jpg token=synthetic-secret\u0000"
        val result = diagnosticFailure(IllegalStateException(text), includeMessage = true)
        assertTrue(result.contains("reason=decode"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("synthetic-secret"))
        assertFalse(result.contains('\u0000'))
    }

    @Test fun referenceTokensDoNotOverrideTheActualFailureReason() {
        assertEquals("decode", mediaDiagnosticReason("decode failed https://example.invalid/timeout.jpg"))
        assertEquals("decode", mediaDiagnosticReason("decode failed /data/unsupported.jpg token=timeout"))
        assertEquals("other", mediaDiagnosticReason("https://example.invalid/corrupt.jpg relative/decoder.jpg token=timeout"))
        assertEquals("invalid_data", mediaDiagnosticReason("invalid image data https://example.invalid/file.jpg"))
        assertEquals("timeout", mediaDiagnosticReason("connection timed out"))
        assertEquals("permission_denied", mediaDiagnosticReason("permission denied /data/private"))
    }

    @Test(timeout = 10_000)
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
