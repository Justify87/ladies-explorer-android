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
import okhttp3.Interceptor
import okhttp3.OkHttpClient

class LadiesApplication : Application(), SingletonImageLoader.Factory {
    val coreStore: CoreStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CoreStore(this) }

    override fun newImageLoader(context: Context): ImageLoader {
        val recorder = coreStore
        val client = OkHttpClient.Builder()
            // Only genuine network exchanges pass this interceptor, not Coil/OkHttp cache hits.
            .addNetworkInterceptor(Interceptor { chain ->
                val request = chain.request()
                if (recorder.imageCaptureMode == CaptureMode.OFF) {
                    return@Interceptor chain.proceed(request)
                }
                val startedMs = System.currentTimeMillis()
                val started = SystemClock.elapsedRealtimeNanos()
                val response = try {
                    chain.proceed(request)
                } catch (failure: java.io.IOException) {
                    recorder.enqueueImageObservation(HttpObservation(
                        method = request.method,
                        url = request.url.toString(),
                        finalUrl = request.url.toString(),
                        status = null,
                        httpVersion = "unknown",
                        requestHeaders = request.headers.toMultimap().flatMap { (name, values) ->
                            values.map { HttpHeader(name, it) }
                        },
                        responseHeaders = emptyList(),
                        mime = null,
                        responseSize = null,
                        startedMs = startedMs.toULong(),
                        headersElapsedMs = ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000).toULong(),
                        bodyElapsedMs = 0uL,
                        body = null,
                        isMedia = true,
                        error = failure.message,
                        bodyOmitted = "network_error",
                    ))
                    throw failure
                }
                val size = response.body?.contentLength()?.takeIf { it >= 0L }
                recorder.enqueueImageObservation(HttpObservation(
                    method = request.method,
                    url = request.url.toString(),
                    finalUrl = response.request.url.toString(),
                    status = response.code.toUShort(),
                    httpVersion = response.protocol.toString(),
                    requestHeaders = request.headers.toMultimap().flatMap { (name, values) ->
                        values.map { HttpHeader(name, it) }
                    },
                    responseHeaders = response.headers.toMultimap().flatMap { (name, values) ->
                        values.map { HttpHeader(name, it) }
                    },
                    mime = response.body?.contentType()?.toString(),
                    responseSize = size?.toULong(),
                    startedMs = startedMs.toULong(),
                    headersElapsedMs = ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000).toULong(),
                    bodyElapsedMs = 0uL,
                    body = null,
                    isMedia = true,
                    error = null,
                    bodyOmitted = "media_body_disabled",
                ))
                response
            })
            .build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = client))
            }
            .build()
    }
}
