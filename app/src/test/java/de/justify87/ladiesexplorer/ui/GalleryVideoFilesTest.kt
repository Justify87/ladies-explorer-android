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
        onDiagnostic: (String) -> Unit = {},
        download: suspend (String) -> ByteArray = { bytes }) =
        GalleryVideoFiles(download, { File.createTempFile("video-", ".media", folder.root) }, budget, warmBudget,
            diagnostic = { _, message, _ -> onDiagnostic(message) })

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
        cache.warm(medium("a"), owner)
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
        cache.warm(medium("a"), owner)
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
        cache.warm(medium("b"), owner)
        assertFalse(firstFile.exists())
        assertEquals(16L, folder.root.listFiles()!!.sumOf { it.length() })
    }

    @Test(timeout = 15_000) fun prefetchBudgetDoesNotPreventOnDemandPlayback() = runBlocking {
        var downloads = 0
        val cache = cache(32L, 16L) { downloads++; bytes }
        val owner = Any()
        cache.warm(medium("a"), owner)
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

    @Test(timeout = 15_000) fun replacementRetainsPinsAndNormalReuseDoesNotRefreshAgain() = runBlocking {
        var downloads = 0
        val cache = cache(16L, 16L) { downloads++; bytes }
        val owner = Any()
        val a = medium("a")
        cache.warm(a, owner)
        val original = cache.withVideo(a) { it.file }
        val replacement = cache.withVideo(a, refresh = true) { it.file }
        assertFalse(original.exists())
        assertEquals(2, downloads)
        assertEquals(replacement, cache.withVideo(a) { it.file })
        assertEquals(2, downloads)
        assertTrue(runCatching { cache.withVideo(medium("b")) {} }.isFailure)
        assertTrue(replacement.exists())
    }

    @Test(timeout = 15_000) fun corruptPinnedEntryRetainsPinAfterRecovery() = runBlocking {
        val cache = cache(16L, 16L)
        val owner = Any()
        val a = medium("a")
        cache.warm(a, owner)
        cache.withVideo(a) { it.file }.writeBytes(ByteArray(16))
        val recovered = cache.withVideo(a) { it.file }
        assertTrue(runCatching { cache.withVideo(medium("b")) {} }.isFailure)
        assertTrue(recovered.isFile)
    }

    @Test(timeout = 15_000) fun refreshWaitsForOldLeaseAndPreservesArchive() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val cache = cache(onDiagnostic = { message ->
            if (message.startsWith("replacement_waiting_for_lease ")) waiting.complete(Unit)
        })
        val a = medium("a")
        val entered = CompletableDeferred<File>()
        val release = CompletableDeferred<Unit>()
        val lease = launch {
            cache.withVideo(a) { entered.complete(it.file); release.await() }
        }
        val refreshed = CompletableDeferred<File>()
        val original = entered.awaitSignal()
        val retry = launch { cache.withVideo(a, refresh = true) { refreshed.complete(it.file) } }
        try {
            waiting.awaitSignal()
            assertTrue(original.isFile)
            assertFalse(refreshed.isCompleted)
        } finally {
            release.complete(Unit)
        }
        lease.join()
        val replacement = withTimeout(3_000) { refreshed.await() }
        retry.join()
        assertTrue(replacement.isFile)
        assertNotEquals(original, replacement)
        assertFalse(original.exists())
        val archive = File(folder.root, "archive.mp4").apply { writeBytes(bytes) }
        val archived = a.copy(localPath = archive.absolutePath)
        assertEquals(archive.canonicalFile, cache.withVideo(archived) { it.file })
        val recovered = cache.withVideo(archived, refresh = true) { it.file }
        assertNotEquals(archive.canonicalFile, recovered)
        assertEquals(recovered, cache.withVideo(archived) { it.file })
        assertTrue(archive.isFile)
        archive.appendBytes(byteArrayOf(1))
        assertEquals(archive.canonicalFile, cache.withVideo(archived) { it.file })
    }

    @Test(timeout = 15_000) fun pruningDuringReplacementDoesNotResurrectPins() = runBlocking {
        var downloads = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = cache(16L, 16L) {
            if (++downloads == 2) { started.complete(Unit); release.await() }
            bytes
        }
        val owner = Any()
        val a = medium("a")
        cache.warm(a, owner)
        val refreshed = CompletableDeferred<File>()
        val retry = launch { cache.withVideo(a, refresh = true) { refreshed.complete(it.file) } }
        started.await()
        cache.retainPins(owner, emptySet())
        release.complete(Unit)
        retry.join()
        val replacement = refreshed.await()
        cache.withVideo(medium("b")) { assertTrue(it.file.isFile) }
        assertFalse(replacement.exists())
    }

    @Test(timeout = 15_000) fun failedReplacementKeepsOldPinnedEntry() = runBlocking {
        var downloads = 0
        val cache = cache(16L, 16L) { if (++downloads == 2) ByteArray(16) else bytes }
        val owner = Any()
        val a = medium("a")
        cache.warm(a, owner)
        val original = cache.withVideo(a) { it.file }
        assertTrue(runCatching { cache.withVideo(a, refresh = true) {} }.isFailure)
        assertTrue(original.isFile)
        assertEquals(original, cache.withVideo(a) { it.file })
        assertTrue(runCatching { cache.withVideo(medium("b")) {} }.isFailure)
    }

    @Test(timeout = 15_000) fun rejectedVideoDoesNotLeaveUnvalidatedFile() = runBlocking {
        val cache = cache { ByteArray(16) }
        assertTrue(runCatching { cache.warm(medium("invalid"), Any()) }.isFailure)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
