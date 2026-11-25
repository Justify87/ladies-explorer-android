package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class ProfilePanelsTest {
    private fun facts() = ProfileFacts(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    private fun detail() = PersonDetail(emptyList(), 0u, 0u, 0u, 0u)

    @Test fun analysisListingCountIsOnlyAFallbackWhenSummaryIsMissing() {
        val stored = detail()
        val withSummary = profileAnalysisFields(2, 1u, stored)
        assertEquals(listOf("Bilder im aktuellen Profil", "Bekannte Namen", "Bekannte Orte",
            "Archivierte Bildreferenzen (Assets)", "Mit anderen Personen geteilte Assets"), withSummary.map { it.first })
        assertEquals("2", withSummary.first().second)
        assertEquals("0", profileAnalysisFields(2, null, stored).toMap()["Anzeigen"])
        assertFalse(profileAnalysisFields(2, null, null).toMap().containsKey("Anzeigen"))
    }

    @Test(timeout = 15_000) fun factsAndAnalysisUseIndependentLocalReadKeysAndErrors() = runBlocking {
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

    @Test(timeout = 15_000) fun lateComparisonAfterCloseCannotReplaceNewComparisonOrReopenDialog() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val newer = listOf(SnapshotDifference("name", "A", "B"))
        val panel = ProfilePanels(this, 7L, { _, _ -> facts() }, { detail() },
            readComparison = { owner, from, to ->
                assertEquals(7L, owner)
                if (from == 1L) {
                    started.complete(Unit)
                    withContext(NonCancellable) { finish.awaitSignal() }
                    listOf(SnapshotDifference("name", "old", "stale"))
                } else {
                    assertEquals(3L, to)
                    newer
                }
            },
        )
        panel.compare(1L, 2L)
        val firstGeneration = panel.comparisonGeneration
        started.awaitSignal()
        val oldJob = coroutineContext[Job]!!.children.single()
        assertTrue(panel.comparing)
        panel.closeComparison()
        assertFalse(panel.comparing)
        assertNull(panel.comparison)
        panel.compare(2L, 3L)
        panel.dismissComparison(firstGeneration)
        assertTrue(panel.comparing)
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

    @Test(timeout = 15_000) fun lateFactsCannotOverwriteNewFilterRevisionWithinSamePanel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val old = ProfileFacts(listOf(ProfileField("fixture", listOf("old"))), emptyList(), emptyList(), emptyList(), emptyList())
        val new = ProfileFacts(listOf(ProfileField("fixture", listOf("new"))), emptyList(), emptyList(), emptyList(), emptyList())
        val panel = ProfilePanels(this, 7L, { _, filters ->
            if (filters == "old") { started.complete(Unit); finish.awaitSignal(); old } else new
        }, { detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "old") }
        started.awaitSignal()
        panel.loadFacts(11L, "new")
        assertEquals(new, panel.facts)
        finish.complete(Unit)
        read.join()
        assertEquals(new, panel.facts)
    }

    @Test(timeout = 15_000) fun factsAndDetailCoalesceInFlightAndSuccessfulReads() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var factsCalls = 0
        var detailCalls = 0
        val panel = ProfilePanels(this, 7L, { _, _ ->
            factsCalls++
            start.complete(Unit)
            finish.awaitSignal()
            facts()
        }, { detailCalls++; detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "{}") }
        start.awaitSignal()
        panel.loadFacts(11L, "{}")
        panel.loadDetail()
        panel.loadDetail()
        finish.complete(Unit)
        read.join()
        panel.loadFacts(11L, "{}")
        assertEquals(1, factsCalls)
        assertEquals(1, detailCalls)
    }

    @Test(timeout = 15_000) fun failedDetailRetriesIndependentlyOnlyByExplicitAction() = runBlocking {
        var attempts = 0
        val panel = ProfilePanels(this, 7L, { _, _ -> facts() },
            { if (++attempts == 1) error("analysis failed") else detail() }, { _, _, _ -> emptyList() })
        panel.loadFacts(11L, "{}")
        val original = panel.facts
        panel.loadDetail()
        panel.loadDetail()
        assertEquals(1, attempts)
        assertEquals("analysis failed", panel.detailError)
        panel.retryDetail(panel.detailGeneration)
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(2, attempts)
        assertNotNull(panel.detail)
        assertNull(panel.detailError)
        assertEquals(original, panel.facts)
    }

    @Test(timeout = 15_000) fun delayedRetryCannotReplayNewerFailureForSamePanel() = runBlocking {
        var factAttempts = 0
        var detailAttempts = 0
        val panel = ProfilePanels(this, 7L,
            { _, _ -> if (++factAttempts < 3) error("same facts error") else facts() },
            { if (++detailAttempts < 3) error("same detail error") else detail() },
            { _, _, _ -> emptyList() })
        panel.loadFacts(11L, "{}")
        panel.loadDetail()
        val oldFacts = panel.factsGeneration
        val oldDetail = panel.detailGeneration
        panel.retryFacts(oldFacts)
        panel.retryDetail(oldDetail)
        assertEquals(2, factAttempts)
        assertEquals(2, detailAttempts)
        assertNotNull(panel.factsError)
        assertNotNull(panel.detailError)
        panel.retryFacts(oldFacts)
        panel.retryDetail(oldDetail)
        assertEquals(2, factAttempts)
        assertEquals(2, detailAttempts)
        panel.retryFacts(panel.factsGeneration)
        panel.retryDetail(panel.detailGeneration)
        assertEquals(3, factAttempts)
        assertEquals(3, detailAttempts)
        assertNotNull(panel.facts)
        assertNotNull(panel.detail)
        panel.close()
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

    @Test fun serviceProjectionRetainsSourceGroupsAndAllValues() {
        val raw = ProfileField("Service", (1..10).map { "v$it" })
        val other = ProfileField("Treffpunkt", listOf("Fixture"))
        val empty = ProfileField("Leer", emptyList())
        val all = ProfileFacts(emptyList(), listOf(raw, other, empty), emptyList(), emptyList(), emptyList()).serviceRows()
        assertEquals(listOf(raw, other), all)
        val (overview, traits) = ProfileFacts(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).overviewAndTraits()
        assertEquals(emptyList<Pair<String, String>>(), overview)
        assertEquals(emptyList<ProfileField>(), traits)
    }

    @Test fun matchSummaryCountsGroupsInsteadOfAlternativesAndOpensByDefault() {
        val groups = listOf(
            FilterMatchGroup("haarfarbe", EvidenceStatus.YES, listOf(
                FilterEvidence("haarfarbe", "blond", EvidenceStatus.YES),
                FilterEvidence("haarfarbe", "schwarz", EvidenceStatus.UNKNOWN))),
            FilterMatchGroup("age", EvidenceStatus.NO,
                listOf(FilterEvidence("age", "25–30 Jahre", EvidenceStatus.NO))),
            FilterMatchGroup("radius", EvidenceStatus.UNKNOWN,
                listOf(FilterEvidence("radius", "20 km", EvidenceStatus.UNKNOWN))),
        )
        assertEquals("1 von 3 belegt · 1 nicht erfüllt · 1 unbekannt", matchSummary(groups))
        val sections = ProfileSections()
        assertTrue(sections.expanded(ProfileSection.Match))
        sections.toggle(ProfileSection.Match)
        assertFalse(sections.expanded(ProfileSection.Match))
        assertFalse(ProfileSections(sections.entries).expanded(ProfileSection.Match))
        sections.restore(emptyList())
        assertTrue(sections.expanded(ProfileSection.Match))
    }

    @Test fun progressiveSectionsKeepStableLazyRowsAndSeparateReadDemand() {
        val rows = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            withSection(ProfileSection.History) {
                item("history-toggle") {}
                item("profile-history-head", ProfileRowRead.Detail) {}
                items(listOf(1, 2), { "event/$it" }, ProfileRowRead.Detail) {}
            }
            item("other") {}
        }.build()
        assertEquals(listOf("hero", "history-toggle", "profile-history-head", "event/1", "event/2", "other"),
            rows.map { it.key })
        assertEquals(listOf(null, ProfileSection.History, ProfileSection.History,
            ProfileSection.History, ProfileSection.History, null), rows.map { it.section })
        assertEquals(emptySet<ProfileRowRead>(), visibleProfileReads(rows, setOf("history-toggle")))
        assertEquals(setOf(ProfileRowRead.Detail), visibleProfileReads(rows, setOf("event/2")))
        val sections = ProfileSections()
        listOf(ProfileSection.Description, ProfileSection.OpeningTimes, ProfileSection.Traits, ProfileSection.Services)
            .forEach { assertEquals(false, sections.expanded(it)) }
    }

    @Test(timeout = 15_000) fun sameKeyFactsRetryCannotResetSuccessfulDetail() = runBlocking {
        var attempts = 0
        val panel = ProfilePanels(this, 7L, { _, _ ->
            if (++attempts == 1) error("facts failed") else facts()
        }, { detail() }, { _, _, _ -> emptyList() })
        panel.loadFacts(11L, "{}")
        panel.loadDetail()
        val previousDetail = panel.detail
        panel.loadFacts(11L, "{}")
        assertEquals(1, attempts)
        panel.retryFacts(panel.factsGeneration)
        panel.retryFacts(panel.factsGeneration)
        coroutineContext[Job]!!.children.toList().forEach { it.join() }
        assertEquals(2, attempts)
        assertNotNull(panel.facts)
        assertNull(panel.factsError)
        assertEquals(previousDetail, panel.detail)
    }

    @Test(timeout = 15_000) fun lateFactsFailureCannotReplaceNewSuccess() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val panel = ProfilePanels(this, 7L, { _, filter ->
            if (filter == "old") { started.complete(Unit); finish.awaitSignal(); error("late") }
            facts()
        }, { detail() }, { _, _, _ -> emptyList() })
        val old = launch { panel.loadFacts(11L, "old") }
        started.awaitSignal()
        panel.loadFacts(11L, "new")
        finish.complete(Unit)
        old.join()
        assertNull(panel.factsError)
        assertNotNull(panel.facts)
    }

    @Test fun demandUsesVisibleLazyItemsNotCollapsedHeaders() {
        val rows = ProfileFlowRows(ProfileMediaSelection()).apply {
            item("hero") {}
            item("analysis-toggle") {}
            item("notes-toggle") {}
            item("profile-services", ProfileRowRead.Facts) {}
            item("profile-analysis", ProfileRowRead.Detail) {}
            item("event/1", ProfileRowRead.Detail) {}
            item("profile-notes", ProfileRowRead.Note) {}
        }.build()
        assertTrue(visibleProfileReads(rows, setOf("hero", "analysis-toggle", "notes-toggle")).isEmpty())
        assertEquals(setOf(ProfileRowRead.Facts, ProfileRowRead.Detail, ProfileRowRead.Note),
            visibleProfileReads(rows, setOf("profile-services", "event/1", "profile-notes")))
    }

    @Test(timeout = 15_000) fun disposedProjectionCannotPublishLateRead() = runBlocking {
        val start = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val panel = ProfilePanels(this, 7L, { _, _ ->
            start.complete(Unit)
            finish.awaitSignal()
            facts()
        }, { detail() }, { _, _, _ -> emptyList() })
        val read = launch { panel.loadFacts(11L, "{}") }
        start.awaitSignal()
        panel.close()
        finish.complete(Unit)
        read.join()
        assertNull(panel.facts)
    }
}
