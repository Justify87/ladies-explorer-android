package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.justify87.ladiesexplorer.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercise the actual shared lazy profile section in both rendered token contexts. */
class PriceProfileDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun swipeContextUsesSharedPriceEditor() = exercise(
        SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
    )

    @Test fun standaloneContextUsesSharedPriceEditor() = exercise(
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
        val rows = mutableListOf<PriceList>()
        var reads = 0
        var writes = 0
        val paid = if (token is SwipeActionToken) PaidEncounter("2026-11-23T12:00:00.000Z", 42) else null
        lateinit var editor: PriceEditor
        editor = PriceEditor({ it == token }, { listing, owner ->
            assertEquals(4L, listing)
            assertEquals(1L, owner)
            reads++
            PriceOverview(rows.sortedWith(compareByDescending<PriceList> { it.values.observedAt }
                .thenByDescending { it.priceListId }), paid)
        }, { edit, values ->
            writes++
            val v = checkNotNull(values)
            rows += PriceList(writes.toLong(), 1, v, listOf(writes.toLong()))
            editor.committed(edit)
        })
        val panelReads = ProfilePanelReads(
            { _, _ -> ProfileFacts(listOf(ProfileField("Fixture trait", listOf("Fixture value"))),
                listOf(ProfileField("Fixture service", listOf("Synthetic"))), emptyList(), emptyList(), emptyList()) },
            { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) },
            { _, _, _ -> emptyList() },
        )
        val notes = NoteEditorActions({ _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> })
        var pxPerDp = 1f
        lateinit var listState: LazyListState
        rule.setContent {
            pxPerDp = LocalDensity.current.density
            MaterialTheme(colorScheme = if (token is SwipeActionToken) ladiesDarkColors else ladiesLightColors) {
                listState = rememberLazyListState()
                ProfileFlow(listState, model, "{}", token, false, emptyMap(), notes,
                    { it == token && editor.edit == null }, { _, _ -> },
                    modifier = Modifier.fillMaxSize().testTag("price-flow"),
                    panelReads = panelReads, prices = editor) {
                    item("hero") { Text("Fixture") }
                }
                PriceEditorDialog(editor)
            }
        }
        rule.runOnIdle { assertEquals(0, reads) }
        rule.onNodeWithTag("price-flow").performScrollToKey("prices-toggle")
        val flow = rule.onNodeWithTag("price-flow")
        fun indexOf(key: String): Int {
            flow.performScrollToKey(key)
            return rule.runOnIdle { listState.layoutInfo.visibleItemsInfo.first { it.key == key }.index }
        }
        val servicesIndex = indexOf("profile-services")
        val pricesIndex = indexOf("prices-toggle")
        val traitsIndex = indexOf("profile-traits")
        assertTrue(servicesIndex < pricesIndex && pricesIndex < traitsIndex)
        flow.performScrollToKey("prices-toggle")
        rule.runOnIdle { assertEquals(0, reads) }
        val section = rule.onNodeWithText("Preise", substring = false)
        assertTrue(section.fetchSemanticsNode().boundsInRoot.height >= 48 * pxPerDp - 1f)
        section.performClick()
        rule.onNodeWithTag("price-flow").performScrollToKey("profile-prices-head")
        if (paid != null) {
            rule.onNodeWithText("Bezahlte Beträge aus Begegnungen").assertExists()
            rule.onNodeWithText("Zuletzt bekannter bezahlter Gesamtbetrag (Begegnungen): 0,42 €", substring = true).assertExists()
        } else rule.onNodeWithText("Zuletzt bekannter bezahlter Gesamtbetrag", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Manuelle Angebotspreise").assertExists()
        rule.onNodeWithText("Keine Preislisten gespeichert.", substring = true).assertDoesNotExist()
        rule.onNodeWithText("aktuelle Gültigkeit", substring = true).assertDoesNotExist()
        val add = rule.onNodeWithText("Preisliste hinzufügen")
        assertTrue(add.fetchSemanticsNode().boundsInRoot.height >= 48 * pxPerDp - 1f)
        add.performClick()
        rule.onNodeWithText("Neue Preisliste").assertExists()
        rule.onNodeWithText("Abbrechen").performClick()
        rule.onNodeWithText("Entwurf verwerfen?").assertExists()
        rule.onNodeWithText("Weiter bearbeiten").performClick()
        rule.onNodeWithText("Datum (TT.MM.JJJJ)").performTextReplacement("ungültig")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithText("Datum bitte als TT.MM.JJJJ eingeben.").assertExists()
        rule.runOnIdle { assertEquals(0, writes); assertNotNull(editor.edit) }
        rule.onNodeWithText("Datum (TT.MM.JJJJ)").performScrollTo().performTextReplacement("24.11.2026")
        rule.onNodeWithText("Betrag in EUR").performScrollTo().performTextReplacement("12,34")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithTag("price-flow").performScrollToKey("price/1")
        rule.onNodeWithText("Neueste erfasste Liste").assertExists()
        rule.onNodeWithText("Manuell erfasst · aktuelle Gültigkeit nicht bestätigt.").assertExists()
        rule.onNodeWithText("12,34 €").assertExists()
        rule.onNodeWithTag("price-flow").performScrollToKey("profile-prices-head")
        rule.onNodeWithText("Preisliste hinzufügen").performClick()
        rule.onNodeWithText("Datum (TT.MM.JJJJ)").performTextReplacement("25.11.2026")
        rule.onNodeWithText("Betrag in EUR").performScrollTo().performTextReplacement("0")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithTag("price-flow").performScrollToKey("price/1")
        rule.onNodeWithText("Frühere Liste").assertExists()
        rule.onAllNodesWithText("Manuell erfasst · aktuelle Gültigkeit nicht bestätigt.").onFirst().assertExists()
        rule.runOnIdle {
            assertEquals(2, writes)
            assertEquals(3, reads)
            assertNull(editor.edit)
            assertEquals(2, rows.size)
            assertEquals(listOf(1234L, 0L), rows.map { it.values.items.single().amountCents })
        }
    }
}
