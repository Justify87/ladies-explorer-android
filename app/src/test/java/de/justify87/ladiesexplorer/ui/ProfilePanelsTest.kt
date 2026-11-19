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
        panel.reload()
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

    @Test fun knownGapDeliveredOldFactsCanOverwriteNewFilterRevisionWithinSamePanel() = runBlocking {
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
        // Compose normally cancels its previous effect. The local read body itself has
        // no revision admission if an older result is nevertheless delivered.
        assertEquals(old, panel.facts)
    }

    @Test fun failedAnalysisReloadClearsPreviousDetailsWithoutErasingFacts() = runBlocking {
        var fail = false
        val stored = detail()
        val content = facts()
        val panel = ProfilePanels(this, 7L, { _, _ -> content },
            { if (fail) error("analysis failed") else stored }, { _, _, _ -> emptyList() })
        panel.loadFacts(11L, "{}")
        panel.loadDetail()
        assertEquals(stored, panel.detail)
        fail = true
        panel.reload()
        panel.loadDetail()
        assertNull(panel.detail)
        assertEquals("analysis failed", panel.detailError)
        assertEquals(content, panel.facts)
        assertNull(panel.factsError)
    }
}
