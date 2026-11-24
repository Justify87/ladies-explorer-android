package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.justify87.ladiesexplorer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the actual shared lazy profile section in both rendered token contexts. */
class EncounterProfileDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun swipeContextUsesSharedEncounterEditor() = exercise(
        SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
    )

    @Test fun standaloneContextUsesSharedEncounterEditor() = exercise(
        DetailIdentityToken(1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
    )

    private fun exercise(token: ProfileIdentityToken) {
        val profile = ProfileResponse(
            listingId = 4, snapshotId = 11, newSnapshot = false, advertId = "fixture",
            name = "Fixture", city = "Fixture city", description = "", postalCode = null,
            hiddenLocation = null, sourceProfileId = null, canonicalId = null,
            phoneDisplay = null, publishedAt = null, traitsJson = "{}", servicesJson = "{}",
            openingTimesJson = "{}", media = emptyList(),
        )
        val model = ProfileReadModel(NativeProfileProjection(
            profile, ProfileIdentity(1, "ACTIVE", null, false, false, false, null),
            PersonSummary(1, "Fixture", Preference.NONE, false, 1u, ""),
            emptyList(), emptyList(), emptyList(),
        ), true)
        val rows = mutableListOf<Encounter>()
        var reads = 0
        var writes = 0
        lateinit var editor: EncounterEditor
        editor = EncounterEditor({ it == token }, { listing, owner ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            reads++
            rows.toList()
        }, { edit, values ->
            writes++
            val v = checkNotNull(values)
            rows += Encounter(5, 1, v, v.occurredAt, v.occurredAt)
            editor.committed(edit)
        })
        val panelReads = ProfilePanelReads(
            { _, _ -> ProfileFacts(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) },
            { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) },
            { _, _, _ -> emptyList() },
        )
        val notes = NoteEditorActions({ _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> })
        rule.setContent {
            MaterialTheme {
                ProfileFlow(rememberLazyListState(), model, "{}", token, false, emptyMap(), notes,
                    { it == token && editor.edit == null }, { _, _ -> },
                    modifier = Modifier.fillMaxSize().testTag("encounter-flow"),
                    panelReads = panelReads, encounters = editor) {
                    item("hero") { Text("Fixture") }
                }
                EncounterEditorDialog(editor)
            }
        }
        rule.runOnIdle { assertEquals(0, reads) }
        rule.onNodeWithTag("encounter-flow").performScrollToKey("encounters-toggle")
        rule.onNodeWithText("Meine Begegnungen").performClick()
        rule.onNodeWithTag("encounter-flow").performScrollToKey("profile-encounters-head")
        rule.onNodeWithText("Begegnung hinzufügen").performClick()
        rule.onNodeWithText("Neue Begegnung").assertExists()
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNodeWithText("Entwurf verwerfen?").assertExists()
        rule.onNodeWithText("Weiter bearbeiten").performClick()
        rule.onNodeWithText("Neue Begegnung").assertExists()
        rule.onNodeWithText("Zeitpunkt (TT.MM.JJJJ HH:mm)").performTextReplacement("24.11.2026 13:00")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithTag("encounter-flow").performScrollToKey("encounter/5")
        rule.onNodeWithText("Bearbeiten").assertExists()
        rule.runOnIdle {
            assertEquals(1, writes)
            assertEquals(2, reads)
            assertNull(editor.edit)
            assertEquals(1, rows.size)
        }
    }
}
