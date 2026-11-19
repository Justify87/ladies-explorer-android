package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedPeopleStateTest {
    @Test fun independentWindowSizesSurviveTabAndRetry() {
        val memory = SavedWindowMemory()
        val favorite = SavedPersonKind.FAVORITE
        val like = SavedPersonKind.LIKE
        val date = SavedPersonSort.DATE
        memory.requestMore(favorite, date, false, 50)
        memory.requestMore(favorite, date, true, 50)
        memory.requestMore(favorite, date, false, 50)
        assertEquals(100u to 100u, memory.limits(favorite, date))
        assertEquals(50u to 100u, memory.limits(like, date))
        assertEquals(50u to 100u, memory.limits(favorite, SavedPersonSort.NAME))
    }

    @Test fun partialStatusBatchProjectsCommittedWritesAfterCancelAndReadFailure() = runBlocking {
        val visited = mutableListOf<Long>()
        val committed = mutableListOf<Long>()
        var cancelled = false
        var projection = 0
        var progress = Triple(0, 0, 0)
        val outcome = writeAndProject(
            write = {
                runSavedStatusBatch(
                    targets = listOf(1L, 2L, 3L, 4L),
                    cancelled = { cancelled },
                    check = {
                        visited += it
                        if (it == 2L) error("timeout")
                        committed += it
                        if (it == 3L) cancelled = true
                    },
                    publish = { total, done, failed, _ -> progress = Triple(total, done, failed) },
                    pause = {},
                )
            },
            project = { projection++; error("read failed") },
        )
        assertEquals(listOf(1L, 2L, 3L), visited)
        assertEquals(listOf(1L, 3L), committed)
        assertEquals(Triple(4, 3, 1), progress)
        assertEquals(1, projection)
        assertEquals(DiscoveryMutationOutcome.ProjectionFailed(true), outcome)
    }
}
