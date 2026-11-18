package de.justify87.ladiesexplorer

import android.app.Application
import android.content.Context
import coil3.annotation.ExperimentalCoilApi
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.NetworkFetcher
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.RustImageNetworkClient

class LadiesApplication : Application(), SingletonImageLoader.Factory {
    val coreStore: CoreStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { CoreStore(this) }

    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(NetworkFetcher.Factory(
                    networkClient = { RustImageNetworkClient(coreStore::loadDisplayImage) },
                    concurrentRequestStrategy = { DeDupeConcurrentRequestStrategy() },
                ))
            }
            .build()
}
