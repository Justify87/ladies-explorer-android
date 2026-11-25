package de.justify87.ladiesexplorer

import android.app.Application
import android.content.Context
import coil3.annotation.ExperimentalCoilApi
import coil3.EventListener
import coil3.decode.Decoder
import coil3.decode.DecodeResult
import coil3.fetch.Fetcher
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.fetch.ImageFetchResult
import de.justify87.ladiesexplorer.ui.mediaRole
import de.justify87.ladiesexplorer.ui.mediaIdentity
import coil3.request.Options
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.ErrorResult
import de.justify87.ladiesexplorer.core.DiagnosticLevel
import de.justify87.ladiesexplorer.core.mediaDiagnosticKey
import coil3.SingletonImageLoader
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.NetworkFetcher
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.RustImageNetworkClient
import de.justify87.ladiesexplorer.ui.GalleryVideoFiles
import java.util.concurrent.atomic.AtomicLong

class LadiesApplication : Application(), SingletonImageLoader.Factory {
    val coreStore: CoreStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CoreStore(this) }
    internal val galleryVideoFiles by lazy { GalleryVideoFiles(coreStore) }
    private val imageRequestIds = AtomicLong()

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .eventListenerFactory { request ->
                val source = if (request.data is java.io.File) "archive" else "url"
                val key = mediaDiagnosticKey(request.data.toString())
                val requestId = imageRequestIds.incrementAndGet()
                val role = request.extras[mediaRole] ?: mediaRole.default
                val identity = request.extras[mediaIdentity].orEmpty().ifBlank { key }
                val contextLabel = "media=$identity source_key=$key request=$requestId role=$role"
                object : EventListener() {
                    private var started = 0L
                    override fun onStart(request: ImageRequest) {
                        started = android.os.SystemClock.elapsedRealtime()
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "start $contextLabel source=$source")
                    }
                    private var phase = "request"
                    private var dataSource = "unknown"
                    private var fetchStarted = 0L
                    private var decodeStarted = 0L
                    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
                        phase = "fetch"
                        fetchStarted = android.os.SystemClock.elapsedRealtime()
                    }
                    override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
                        dataSource = when (result) {
                            is SourceFetchResult -> result.dataSource.name
                            is ImageFetchResult -> result.dataSource.name
                            else -> "unknown"
                        }
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG,
                            "fetch_end $contextLabel result=${result != null} cache=$dataSource elapsed_ms=${android.os.SystemClock.elapsedRealtime() - fetchStarted}")
                        phase = "after_fetch"
                    }
                    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
                        phase = "decode"
                        decodeStarted = android.os.SystemClock.elapsedRealtime()
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "decode_start $contextLabel decoder=${decoder.javaClass.simpleName}")
                    }
                    override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG,
                            "decode_end $contextLabel result=${result != null} cache=$dataSource elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted}")
                        phase = "after_decode"
                    }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG,
                            "success $contextLabel source=$source cache=${result.dataSource} width=${result.image.width} height=${result.image.height} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    }
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.WARN,
                            "failed $contextLabel source=$source phase=$phase cache=$dataSource elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}", result.throwable)
                    }
                    override fun onCancel(request: ImageRequest) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "cancelled $contextLabel source=$source")
                    }
                }
            }
            .components {
                add(NetworkFetcher.Factory(
                    networkClient = { RustImageNetworkClient(coreStore::loadDisplayImage) },
                    concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                ))
            }
            .build()
}
