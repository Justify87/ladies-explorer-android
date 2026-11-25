package de.justify87.ladiesexplorer

import android.app.Application
import android.content.Context
import coil3.annotation.ExperimentalCoilApi
import coil3.EventListener
import coil3.decode.Decoder
import coil3.decode.DecodeResult
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

class LadiesApplication : Application(), SingletonImageLoader.Factory {
    val coreStore: CoreStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CoreStore(this) }
    internal val galleryVideoFiles by lazy { GalleryVideoFiles(coreStore) }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .eventListenerFactory { request ->
                val source = if (request.data is java.io.File) "archive" else "url"
                val key = mediaDiagnosticKey(request.data.toString())
                object : EventListener() {
                    private var started = 0L
                    override fun onStart(request: ImageRequest) {
                        started = android.os.SystemClock.elapsedRealtime()
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "start media=$key source=$source")
                    }
                    private var phase = "request"
                    private var decodeStarted = 0L
                    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
                        phase = "decode"
                        decodeStarted = android.os.SystemClock.elapsedRealtime()
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "decode_start media=$key decoder=${decoder.javaClass.simpleName}")
                    }
                    override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG,
                            "decode_end media=$key result=${result != null} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted}")
                        phase = "after_decode"
                    }
                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG,
                            "success media=$key source=$source cache=${result.dataSource} width=${result.image.width} height=${result.image.height} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}")
                    }
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.WARN,
                            "failed media=$key source=$source phase=$phase elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started}", result.throwable)
                    }
                    override fun onCancel(request: ImageRequest) {
                        coreStore.diagnostic("COIL", DiagnosticLevel.DEBUG, "cancelled media=$key source=$source")
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
