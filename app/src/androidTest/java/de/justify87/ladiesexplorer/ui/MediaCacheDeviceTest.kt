package de.justify87.ladiesexplorer.ui

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.DataSource
import coil3.disk.DiskCache
import coil3.network.NetworkFetcher
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import de.justify87.ladiesexplorer.core.DisplayImage
import de.justify87.ladiesexplorer.core.RustImageNetworkClient
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MediaCacheDeviceTest {
    @OptIn(ExperimentalCoilApi::class)
    @Test(timeout = 30_000) fun failedDiskDecodeCanBeReplacedAndReused() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "synthetic-coil-${UUID.randomUUID()}")
        val cache = DiskCache.Builder().directory(directory.absolutePath.toPath())
            .maxSizeBytes(1024L * 1024).build()
        val url = "https://example.invalid/synthetic.jpg"
        val editor = checkNotNull(cache.openEditor(url))
        cache.fileSystem.write(editor.metadata) { }
        cache.fileSystem.write(editor.data) { writeUtf8("invalid synthetic image") }
        editor.commit()
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
        image.recycle()
        val downloads = AtomicInteger()
        val loader = ImageLoader.Builder(context).diskCache(cache).memoryCache(null)
            .components {
                add(NetworkFetcher.Factory(networkClient = {
                    RustImageNetworkClient { requested ->
                        assertEquals(url, requested)
                        downloads.incrementAndGet()
                        DisplayImage(png, "image/png")
                    }
                }))
            }.build()
        try {
            val size = IntSize(16, 16)
            assertTrue(loader.execute(heroImageRequest(context, url, size)) is ErrorResult)
            assertEquals(0, downloads.get())
            assertTrue(loader.execute(heroImageRequest(context, url, size, refresh = true)) is SuccessResult)
            assertEquals(1, downloads.get())
            val reused = loader.execute(heroImageRequest(context, url, size)) as SuccessResult
            assertEquals(DataSource.DISK, reused.dataSource)
            assertEquals(1, downloads.get())
        } finally {
            loader.shutdown()
            cache.shutdown()
            directory.deleteRecursively()
        }
    }
}
