package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedPeopleStateTest {
    private fun row(id: Long) = SavedPersonRow(
        personId = id, name = "Fixture", preference = Preference.LIKE, favorite = true,
        card = null, listingCount = 0u, updatedAt = "", lastSeenAt = "",
        status = "UNKNOWN", statusCheckedAt = null, latestChangeAt = null,
        latestChangeType = null, recent = false,
    )

    private fun cursor(id: Long) = SavedPersonCursor(
        SavedPersonKind.LIKE, SavedPersonSort.DATE, SavedPersonSection.ALL, 42L, "", id,
    )

    @Test fun refreshRebuildsLoadedWindowFromNewCursorsAndDeduplicates() = runBlocking {
        val calls = mutableListOf<SavedPersonCursor?>()
        val result = readSavedWindow(3) { after ->
            calls += after
            if (after == null) SavedPersonPage(listOf(row(5), row(4)), 3u, 3u, 42L, cursor(4))
            else SavedPersonPage(listOf(row(4), row(3)), 3u, 3u, 42L, null)
        }
        assertEquals(listOf(null, cursor(4)), calls)
        assertEquals(listOf(5L, 4L, 3L), result.rows.map { it.personId })
        assertEquals(3u, result.total)
        assertNull(result.nextCursor)
    }

    @Test fun projectionFailureDoesNotRepeatPersistedDecision() = runBlocking {
        var writes = 0
        val result = writeAndProject(
            write = { writes++ },
            project = { error("read failed") },
        )
        assertEquals(1, writes)
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(true), result)
    }
}
