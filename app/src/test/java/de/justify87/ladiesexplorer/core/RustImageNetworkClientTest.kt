package de.justify87.ladiesexplorer.core

import coil3.network.NetworkRequest
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RustImageNetworkClientTest {
    @Test
    fun delegatesExactRevisionUrlAndReturnsEncodedBody() = runBlocking {
        val url = "https://bilder1.ladies-cdn.de/a.jpg?h=revision"
        val bytes = byteArrayOf(1, 2, 3)
        var calls = 0
        val client = RustImageNetworkClient { requested ->
            calls++
            assertEquals(url, requested)
            DisplayImage(bytes, "image/jpeg")
        }
        val result = client.executeRequest(NetworkRequest(url)) { response ->
            assertEquals(200, response.code)
            assertEquals("image/jpeg", response.headers["Content-Type"])
            val output = Buffer()
            checkNotNull(response.body).writeTo(output)
            output.readByteArray()
        }
        assertArrayEquals(bytes, result)
        assertEquals(1, calls)
    }

    @Test
    fun rejectsNonGetAndDoesNotFallbackOnFailure() = runBlocking {
        var calls = 0
        val failure = IllegalStateException("synthetic failure")
        val client = RustImageNetworkClient {
            calls++
            throw failure
        }
        val url = "https://bilder1.ladies-cdn.de/a.jpg"
        val rejected = runCatching {
            client.executeRequest(NetworkRequest(url, method = "POST")) { error("Unexpected response") }
        }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, calls)
        val failed = runCatching {
            client.executeRequest(NetworkRequest(url)) { error("Unexpected response") }
        }
        assertSame(failure, failed.exceptionOrNull())
        assertEquals(1, calls)
    }
}
