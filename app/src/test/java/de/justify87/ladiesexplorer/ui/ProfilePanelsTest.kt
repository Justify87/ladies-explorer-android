package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        val oldReturned = CompletableDeferred<Unit>()
        val newer = listOf(SnapshotDifference("name", "A", "B"))
        val panel = ProfilePanels(this, 7L, { _, _ -> facts() }, { detail() },
            readComparison = { owner, from, to ->
                assertEquals(7L, owner)
                if (from == 1L) {
                    started.complete(Unit)
                    finish.await()
                    oldReturned.complete(Unit)
                    listOf(SnapshotDifference("name", "old", "stale"))
                } else {
                    assertEquals(3L, to)
                    newer
                }
            },
        )
        panel.compare(1L, 2L)
        started.await()
        assertTrue(panel.comparing)
        panel.closeComparison()
        assertFalse(panel.comparing)
        assertNull(panel.comparison)
        panel.compare(2L, 3L)
        yield()
        assertFalse(panel.comparing)
        assertEquals(newer, panel.comparison)
        finish.complete(Unit)
        oldReturned.await()
        yield()
        assertEquals(newer, panel.comparison)
        assertNull(panel.comparisonError)
        panel.closeComparison()
        assertNull(panel.comparison)
        assertFalse(panel.comparing)
    }
}
