package de.justify87.ladiesexplorer

import android.app.Application
import android.content.Context
import android.os.SystemClock
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import de.justify87.ladiesexplorer.core.CaptureMode
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.HttpHeader
import de.justify87.ladiesexplorer.core.HttpObservation
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class LadiesApplication : Application(), SingletonImageLoader.Factory {
    val coreStore: CoreStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CoreStore(this) }

    override fun newImageLoader(context: Context): ImageLoader {
        val recorder = coreStore
        // Each listener sees real network exchanges (including redirects), never cached images.
        // Body completion is observed without reading or wrapping Coil's response stream.
        val client = OkHttpClient.Builder()
            .eventListenerFactory(EventListener.Factory {
                if (recorder.imageCaptureMode == CaptureMode.OFF) EventListener.NONE
                else ImageHttpListener(recorder, recorder.currentCaptureEpoch())
            })
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
            }
            .build()
    }
}

private class ImageHttpListener(private val recorder: CoreStore, private val epoch: Long) : EventListener() {
    private var request: Request? = null
    private var pending: HttpObservation? = null
    private var startedMs = 0L
    private var startedNanos = 0L
    private var headersNanos = 0L

    override fun requestHeadersStart(call: Call) {
        // OkHttp invokes this again for the next exchange after an HTTP redirect or retry.
        complete("body_not_observed")
        startedMs = System.currentTimeMillis()
        startedNanos = SystemClock.elapsedRealtimeNanos()
        request = null
    }

    override fun requestHeadersEnd(call: Call, request: Request) {
        this.request = request
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        val outgoing = request ?: return
        headersNanos = SystemClock.elapsedRealtimeNanos()
        pending = HttpObservation(
            method = outgoing.method,
            url = outgoing.url.toString(),
            finalUrl = response.request.url.toString(),
            status = response.code.toUShort(),
            httpVersion = response.protocol.toString(),
            requestHeaders = outgoing.headers.asCaptureHeaders(),
            responseHeaders = response.headers.asCaptureHeaders(),
            mime = response.header("Content-Type"),
            responseSize = response.header("Content-Length")?.toLongOrNull()
                ?.takeIf { it >= 0L }?.toULong(),
            startedMs = startedMs.toULong(),
            headersElapsedMs = ((headersNanos - startedNanos) / 1_000_000).toULong(),
            bodyElapsedMs = 0uL,
            body = null,
            isMedia = true,
            error = null,
            bodyOmitted = "body_not_observed",
        )
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        complete("media_body_disabled", byteCount)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        if (pending != null) {
            complete("network_error", error = ioe.message)
        } else {
            val outgoing = request ?: return
            recorder.enqueueImageObservation(HttpObservation(
                method = outgoing.method,
                url = outgoing.url.toString(),
                finalUrl = outgoing.url.toString(),
                status = null,
                httpVersion = "unknown",
                requestHeaders = outgoing.headers.asCaptureHeaders(),
                responseHeaders = emptyList(),
                mime = null,
                responseSize = null,
                startedMs = startedMs.toULong(),
                headersElapsedMs = ((SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000).toULong(),
                bodyElapsedMs = 0uL,
                body = null,
                isMedia = true,
                error = ioe.message,
                bodyOmitted = "network_error",
            ), epoch)
        }
        request = null
    }

    override fun callEnd(call: Call) {
        complete("body_not_observed")
    }

    private fun complete(reason: String, byteCount: Long? = null, error: String? = null) {
        val observed = pending ?: return
        pending = null
        recorder.enqueueImageObservation(
            observed.copy(
                responseSize = byteCount?.takeIf { it >= 0 }?.toULong() ?: observed.responseSize,
                bodyElapsedMs = if (byteCount != null || error != null) {
                    ((SystemClock.elapsedRealtimeNanos() - headersNanos) / 1_000_000).toULong()
                } else 0uL,
                error = error,
                bodyOmitted = reason,
            ), epoch
        )
        request = null
    }
}

private fun okhttp3.Headers.asCaptureHeaders(): List<HttpHeader> =
    (0 until size).map { index -> HttpHeader(name(index), value(index)) }
