package de.justify87.ladiesexplorer.ui

import androidx.compose.ui.unit.IntSize
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePreparationWindowTest {
    private val size = IntSize(360, 640)
    private val panelData = PreparedPanelData(null, null, "facts unavailable", "detail unavailable")
    private val first = ProfileMediaItem("VIDEO", "https://example.invalid/a.mp4", null, 0u)
    private val second = ProfileMediaItem("IMAGE", "https://example.invalid/b.jpg", null, 1u)
    private fun model(revision: Long = 1L): ProfileReadModel {
        val fixture = fixtureSwipe(4L, 1L)
        return fixture.copy(revision = revision, local = fixture.local.copy(media = listOf(first, second)))
    }

    @Test(timeout = 15_000) fun publishesOnlyAfterEveryMediumAndPanelsAreSettled() = runBlocking {
        val window = ProfilePreparationWindow(imageBudget = 0)
        val model = model()
        val key = 4L to 1L
        window.retain(setOf(key))
        val reads = mutableListOf<String>()
        suspend fun step() = window.step(model, "{}", size,
            readPanels = { reads += "panels"; panelData },
            readMedium = { item, remaining ->
                assertEquals(0L, remaining)
                reads += item.kind
                if (item.kind == "VIDEO") PreparedMedium.Video else PreparedMedium.Unavailable
            })
        step()
        assertNull(window.displayed(key))
        step()
        assertNull(window.displayed(key))
        step()
        val prepared = requireNotNull(window.displayed(key))
        assertTrue(prepared.complete)
        assertTrue(prepared.hasMediaFailures)
        assertTrue(window.ready(model, "{}", size))
        assertEquals(listOf("panels", "VIDEO", "IMAGE"), reads)
        assertEquals(setOf(first.url), window.pinnedVideos())
        step()
        assertEquals(3, reads.size)
    }

    @Test fun preparedPanelErrorsDoNotMasqueradeAsMediaFailures() {
        val prepared = PreparedProfile(model(), "{}", size, panels = panelData)
        assertFalse(prepared.hasMediaFailures)
        assertTrue(prepared.copy(media = mapOf(first.presentationKey() to PreparedMedium.Unavailable)).hasMediaFailures)
    }

    @Test(timeout = 15_000) fun sameSnapshotReprojectionKeepsGalleryAndReplacesPanelsAtomically() = runBlocking {
        val window = ProfilePreparationWindow()
        val key = 4L to 1L
        val initial = model()
        window.retain(setOf(key))
        repeat(3) {
            window.step(initial, "{}", size, { panelData }, { item, _ ->
                if (item.kind == "VIDEO") PreparedMedium.Video else PreparedMedium.Unavailable
            })
        }
        val prior = requireNotNull(window.displayed(key))
        val newModel = model(2L)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pending = launch {
            window.step(newModel, "{}", size, {
                entered.complete(Unit)
                finish.await()
                panelData
            }, { _, _ -> error("same snapshot must reuse prepared media") })
        }
        entered.await()
        assertSame(prior, window.displayed(key))
        assertFalse(window.ready(newModel, "{}", size))
        finish.complete(Unit)
        pending.join()
        val next = requireNotNull(window.displayed(key))
        assertEquals(newModel, next.model)
        assertEquals(prior.media, next.media)
        assertTrue(window.ready(newModel, "{}", size))
    }

    @Test(timeout = 15_000) fun prunedOrSupersededWorkCannotPublish() = runBlocking {
        val window = ProfilePreparationWindow()
        val key = 4L to 1L
        val model = model()
        window.retain(setOf(key))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val pending = launch {
            window.step(model, "{}", size, {
                entered.complete(Unit)
                finish.await()
                panelData
            }, { _, _ -> error("not reached") })
        }
        entered.await()
        window.retain(emptySet())
        window.retain(setOf(key))
        finish.complete(Unit)
        pending.join()
        assertNull(window.displayed(key))
        window.step(model, "{}", size, { panelData }, { _, _ -> error("not reached") }, accept = { false })
        assertNull(window.displayed(key))
        window.step(model, "{}", size, { panelData }, { _, _ -> error("not reached") })
        assertNull(window.displayed(key))
        window.step(model, "{}", size, { error("panels already read") }, { _, _ -> PreparedMedium.Video })
        window.step(model, "{}", size, { error("panels already read") }, { _, _ -> PreparedMedium.Unavailable })
        assertTrue(window.ready(model, "{}", size))
    }

    @Test fun priorityIsRecomputedFromCurrentOrderBetweenWorkUnits() {
        val pending = mutableSetOf(2, 3, 4)
        assertEquals(2, nextProfileWork(listOf(1, 2, 3, 4)) { it in pending })
        assertEquals(4, nextProfileWork(listOf(4, 2, 3)) { it in pending })
        pending.remove(4)
        assertEquals(2, nextProfileWork(listOf(4, 2, 3)) { it in pending })
        assertNull(nextProfileWork(listOf(1)) { it in pending })
    }

    @Test(timeout = 15_000) fun preparedPanelResultsSuppressDuplicateVisibilityReads() = runBlocking {
        val panels = ProfilePanels(this, 1L, { _, _ -> error("duplicate facts") },
            { error("duplicate detail") }, { _, _, _ -> emptyList() }, panelData, 11L, "{}")
        panels.loadFacts(11L, "{}")
        panels.loadDetail()
        assertEquals(panelData.factsError, panels.factsError)
        assertEquals(panelData.detailError, panels.detailError)
        panels.close()
    }
}
