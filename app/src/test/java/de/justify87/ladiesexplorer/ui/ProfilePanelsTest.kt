package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ProfilePanelsTest {
    private fun facts() = ProfileFacts(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun detail() = PersonDetail(emptyList(), 0u, 0u, 0u, 0u)

    @Test fun factsAndAnalysisUseIndependentLocalReadKeysAndErrors() = runBlocking {
        val calls = mutableListOf<String>()
        val storedDetail = detail()
        var failFacts = true
        val panel = ProfilePanels(this, 7L,
            readFacts = { snapshot, filters ->
                calls += "facts:$snapshot:$filters"
                if (failFacts) error("facts failed")
                facts()
            },
            readDetail = { owner -> calls += "detail:$owner"; storedDetail },
            readComparison = { _, _, _ -> error("not requested") },
        )
        panel.loadFacts(null, "{}")
        assertTrue(calls.isEmpty())
        assertNotNull(panel.factsError)
        panel.loadFacts(11L, "{}")
        assertEquals("facts failed", panel.factsError)
        panel.loadDetail()
        assertEquals(storedDetail, panel.detail)
        assertNull(panel.detailError)
        failFacts = false
        panel.loadFacts(12L, "{}")
        assertNotNull(panel.facts)
        assertNull(panel.factsError)
        assertEquals(storedDetail, panel.detail)
        assertEquals(listOf("facts:11:{}", "detail:7", "facts:12:{}"), calls)
    }

    @Test fun lateComparisonAfterCloseCannotReplaceNewComparisonOrReopenDialog() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val newer = listOf(SnapshotDifference("name", "A", "B"))
        val panel = ProfilePanels(this, 7L, { _, _ -> facts() }, { detail() },
            readComparison = { owner, from, to ->
                assertEquals(7L, owner)
                if (from == 1L) {
                    started.complete(Unit)
                    finish.await()
                    listOf(SnapshotDifference("name", "old", "stale"))
                } else {
                    assertEquals(3L, to)
                    newer
                }
            },
        )
        panel.compare(1L, 2L)
        started.await()
        val oldJob = coroutineContext[Job]!!.children.single()
        assertTrue(panel.comparing)
        panel.closeComparison()
        assertFalse(panel.comparing)
        assertNull(panel.comparison)
        panel.compare(2L, 3L)
        coroutineContext[Job]!!.children.single { it != oldJob }.join()
        assertFalse(panel.comparing)
        assertEquals(newer, panel.comparison)
        finish.complete(Unit)
        oldJob.join()
        assertEquals(newer, panel.comparison)
        assertNull(panel.comparisonError)
        panel.closeComparison()
        assertNull(panel.comparison)
        assertFalse(panel.comparing)
    }

    @Test fun lateFactsCannotOverwriteNewFilterRevisionWithinSamePanel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = ProfileFacts(listOf(ProfileField("fixture", listOf("old"))), emptyList(), emptyList(), emptyList(), emptyList())
        val new = ProfileFacts(listOf(ProfileField("fixture", listOf("new"))), emptyList(), emptyList(), emptyList(), emptyList())
        val panel = ProfilePanels(this, 7L, { _, filters ->
            if (filters == "old") { started.complete(Unit); finish.await(); old } else new
        }, { detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "old") }
        started.await()
        panel.reload()
        panel.loadFacts(11L, "new")
        assertEquals(new, panel.facts)
        finish.complete(Unit)
        read.join()
        assertEquals(new, panel.facts)
    }

    @Test fun factsAndDetailCoalesceInFlightAndSuccessfulReads() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var factsCalls = 0
        var detailCalls = 0
        val panel = ProfilePanels(this, 7L, { _, _ ->
            factsCalls++
            start.complete(Unit)
            finish.await()
            facts()
        }, { detailCalls++; detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "{}") }
        start.await()
        panel.loadFacts(11L, "{}")
        panel.loadDetail()
        panel.loadDetail()
        finish.complete(Unit)
        read.join()
        panel.loadFacts(11L, "{}")
        assertEquals(1, factsCalls)
        assertEquals(1, detailCalls)
    }

    @Test fun failedDetailRetriesIndependentlyOnlyByExplicitAction() = runBlocking {
        var attempts = 0
        val panel = ProfilePanels(this, 7L, { _, _ -> facts() },
            { if (++attempts == 1) error("analysis failed") else detail() }, { _, _, _ -> emptyList() })
        panel.loadFacts(11L, "{}")
        val original = panel.facts
        panel.loadDetail()
        panel.loadDetail()
        assertEquals(1, attempts)
        assertEquals("analysis failed", panel.detailError)
        panel.retryDetail()
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(2, attempts)
        assertNotNull(panel.detail)
        assertNull(panel.detailError)
        assertEquals(original, panel.facts)
    }

    @Test fun promotedTraitDoesNotDiscardDistinctAliases() {
        val first = ProfileField("Busen", listOf("A"))
        val second = ProfileField("Oberweite", listOf("B"))
        val other = ProfileField("Fixture", listOf("C"))
        val (overview, traits) = ProfileFacts(listOf(first, second, other), emptyList(),
            emptyList(), emptyList(), emptyList()).overviewAndTraits()
        assertEquals(listOf("Busen" to "A"), overview)
        assertEquals(listOf(second, other), traits)
    }

    @Test fun demandUsesVisibleLazyItemsNotCollapsedHeaders() {
        assertFalse(factsDemand(setOf("hero", "header")))
        assertFalse(detailDemand(setOf("analysis-toggle", "history-toggle", "notes-toggle")))
        assertTrue(factsDemand(setOf("profile-services")))
        assertTrue(factsDemand(setOf("profile-facts")))
        assertTrue(detailDemand(setOf("profile-analysis")))
        assertTrue(detailDemand(setOf("event/1")))
    }

    @Test fun disposedProjectionCannotPublishLateRead() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val panel = ProfilePanels(this, 7L, { _, _ ->
            start.complete(Unit)
            finish.await()
            facts()
        }, { detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "{}") }
        start.await()
        panel.close()
        finish.complete(Unit)
        read.join()
        assertNull(panel.facts)
    }
}
