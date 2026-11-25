package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.ProfileMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GalleryVideoFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = ByteArray(16).apply {
        byteArrayOf(102, 116, 121, 112).copyInto(this, 4)
    }
    private fun medium(name: String) = ProfileMediaItem("VIDEO", "https://example.invalid/$name.mp4", null, 0u)
    private fun cache(budget: Long = 64L, warmBudget: Long = 48L,
        download: suspend (String) -> ByteArray = { bytes }) =
        GalleryVideoFiles(download, { File.createTempFile("video-", ".media", folder.root) }, budget, warmBudget)

    @Test(timeout = 15_000) fun cachedPlaybackDoesNotWaitForUnrelatedDownload() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var reads = 0
        val cache = cache { url ->
            reads++
            if (url == medium("b").url) {
                started.complete(Unit)
                finish.await()
            }
            bytes
        }
        val owner = Any()
        assertTrue(cache.warm(medium("a"), owner))
        val work = launch { cache.warm(medium("b"), owner) }
        try {
            started.await()
            withTimeout(2_000) {
                cache.withVideo(medium("a")) { assertTrue(it.file.isFile) }
            }
            assertEquals(2, reads)
        } finally {
            finish.complete(Unit)
            work.join()
        }
    }

    @Test(timeout = 15_000) fun pinnedFilesAndPlaybackLeasesCannotBeEvicted() = runBlocking {
        val cache = cache(16L, 16L)
        val owner = Any()
        assertTrue(cache.warm(medium("a"), owner))
        val firstFile = cache.withVideo(medium("a")) { it.file }
        assertTrue(runCatching { cache.warm(medium("b"), owner) }.isFailure)
        assertTrue(firstFile.isFile)
        cache.retainPins(owner, emptySet())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val player = launch {
            cache.withVideo(medium("a")) { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            assertTrue(runCatching { cache.warm(medium("b"), owner) }.isFailure)
            assertTrue(firstFile.isFile)
        } finally {
            release.complete(Unit)
            player.join()
        }
        assertTrue(cache.warm(medium("b"), owner))
        assertFalse(firstFile.exists())
        assertEquals(16L, folder.root.listFiles()!!.sumOf { it.length() })
    }

    @Test(timeout = 15_000) fun prefetchBudgetDoesNotPreventOnDemandPlayback() = runBlocking {
        var downloads = 0
        val cache = cache(32L, 16L) { downloads++; bytes }
        val owner = Any()
        assertTrue(cache.warm(medium("a"), owner))
        assertTrue(runCatching { cache.warm(medium("b"), owner) }.exceptionOrNull() is VideoPreparationDeferred)
        assertEquals(1, downloads)
        cache.withVideo(medium("b")) { assertTrue(it.file.isFile) }
        assertEquals(2, downloads)
    }

    @Test(timeout = 15_000) fun corruptedCacheEntryIsDownloadedAgain() = runBlocking {
        var downloads = 0
        val cache = cache { downloads++; bytes }
        val original = cache.withVideo(medium("a")) { it.file }
        original.writeBytes(ByteArray(16))
        cache.withVideo(medium("a")) {
            assertTrue(it.file.isFile)
            assertTrue(it.file.readBytes().contentEquals(bytes))
        }
        assertEquals(2, downloads)
    }

    @Test(timeout = 15_000) fun rejectedVideoDoesNotLeaveUnvalidatedFile() = runBlocking {
        val cache = cache { ByteArray(16) }
        assertTrue(runCatching { cache.warm(medium("invalid"), Any()) }.isFailure)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
