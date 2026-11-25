package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real ProfileFlow with synthetic readers. */
class ProfileRestoreDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun profileMatchContextEncodesNativeContract() {
        val local = defaultLocalVisibility().copy(videoOnly = true, imageMode = "AI")
        val run = org.json.JSONObject(ProfileMatchContext.Run(11L, 7L, local).encode())
        assertEquals("run", run.getString("kind"))
        assertEquals(11L, run.getLong("run_id"))
        assertEquals(7L, run.getLong("owner_id"))
        assertTrue(run.getJSONObject("local").getBoolean("video_only"))
        assertEquals("AI", run.getJSONObject("local").getString("image_mode"))
        val saved = org.json.JSONObject(ProfileMatchContext.Saved("fixture", 8L).encode())
        assertEquals("saved", saved.getString("kind"))
        assertEquals("fixture", saved.getString("saved_id"))
        assertEquals(8L, saved.getLong("owner_id"))
        assertEquals("{}", (null as ProfileMatchContext?).encode())
    }

    private fun profile(snapshot: Long = 11, phone: String? = null,
        media: List<ProfileMediaItem> = listOf(ProfileMediaItem("IMAGE", "", null, 0u),
            ProfileMediaItem("VIDEO", "https://example.invalid/fixture", null, 1u)),
    ) = ProfileReadModel(
        NativeProfileProjection(
            ProfileResponse(listingId = 4, snapshotId = snapshot, newSnapshot = false, advertId = "fixture",
                name = "Fixture", city = "Fixture", description = "Fixture ".repeat(120), postalCode = null,
                hiddenLocation = "Offenbach am Main", sourceProfileId = null, canonicalId = null,
                phoneDisplay = phone, publishedAt = null, traitsJson = "{}", servicesJson = "{}",
                openingTimesJson = "{}", media = emptyList()),
            ProfileIdentity(personId = 1, status = "ACTIVE", profileUrl = null,
                personNew = false, listingNew = false, hasVideo = false, age = null),
            PersonSummary(1, "Fixture", Preference.NONE, false, 1u, ""),
            media,
            emptyList(), emptyList(),
        ), true,
    )

    private fun token(model: ProfileReadModel) = SwipeActionToken(
        3, 1, model.profile.listingId, model.identity.personId, model.profile.snapshotId, model.revision, Preference.NONE, false, emptyList(),
    )

    private fun facts() = ProfileFacts(emptyList(),
        listOf(ProfileField("Fixture", (1..25).map { "Value $it" })), emptyList(), emptyList(), emptyList())

    private fun reads(factsRead: suspend () -> ProfileFacts) = ProfilePanelReads(
        { _, _ -> factsRead() }, { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) }, { _, _, _ -> emptyList() })

    private val notes = NoteEditorActions({ _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> })

    @Test fun sharedProfileShowsContactsDirectlyAfterGalleryAndOnlyCanonicalPlace() {
        // Both contexts use the same ProfileFlow rows; no real contact intents are launched.
        var model by mutableStateOf(profile(phone = "+49 170 1234567"))
        var busy by mutableStateOf(false)
        var standalone by mutableStateOf(false)
        var admitted by mutableStateOf(true)
        val readers = reads { facts() }
        fun currentToken(): ProfileIdentityToken = if (standalone)
            DetailIdentityToken(1, model.profile.listingId, model.identity.personId, model.profile.snapshotId,
                model.revision, model.person.preference, model.person.favorite, model.candidates)
        else token(model)
        rule.setContent {
            LadiesTheme(darkTheme = standalone) {
                ProfileFlow(rememberLazyListState(), model, "{}", currentToken().takeIf { admitted }, busy, emptyMap(), notes,
                    isProfileCurrent = { admitted && it == currentToken() }, onConfirmIdentity = { _, _ -> },
                    modifier = Modifier.fillMaxSize().testTag("compact-profile"), panelReads = readers) {
                    item(if (standalone) "media" else "hero") {
                        Text("Galerie", Modifier.height(72.dp))
                    }
                }
            }
        }
        repeat(2) {
            val gallery = rule.onNodeWithText("Galerie").fetchSemanticsNode().boundsInRoot
            val phone = rule.onNodeWithText("Telefon").fetchSemanticsNode().boundsInRoot
            val whatsapp = rule.onNodeWithText("WhatsApp").fetchSemanticsNode().boundsInRoot
            rule.onAllNodesWithText("Fixture", substring = false).assertCountEquals(1)
            val heading = rule.onNodeWithText("Fixture").fetchSemanticsNode().boundsInRoot
            assertTrue(phone.top >= gallery.bottom)
            assertTrue(whatsapp.top >= gallery.bottom)
            assertTrue(phone.bottom <= heading.top)
            assertTrue(whatsapp.bottom <= heading.top)
            rule.onNodeWithText("Fixture · Zuletzt bekannter Quellenstatus: Anzeige aktiv").assertExists()
            rule.onAllNodesWithText("Anzeigen: 1").assertCountEquals(1)
            rule.onNodeWithText("Offenbach am Main").assertDoesNotExist()
            rule.onNodeWithText("Übersicht").assertDoesNotExist()
            rule.runOnIdle { busy = true }
            rule.onNodeWithText("Telefon").assertIsNotEnabled()
            rule.onNodeWithText("WhatsApp").assertIsNotEnabled()
            rule.runOnIdle { busy = false; admitted = false }
            rule.onNodeWithText("Telefon").assertIsNotEnabled()
            rule.onNodeWithText("WhatsApp").assertIsNotEnabled()
            rule.runOnIdle { admitted = true; standalone = true }
        }
        rule.runOnIdle {
            val next = profile(snapshot = 12, phone = "invalid")
            model = next.copy(local = next.local.copy(
                profile = next.profile.copy(listingId = 8, name = "Neue Anzeige", city = "Neuer Ort",
                    publishedAt = "2026-11-24T23:15:00+02:00"),
                identity = next.identity.copy(personId = 2, age = "30", status = "UNKNOWN"),
                person = next.person.copy(personId = 2, listingCount = 2u)))
        }
        rule.onNodeWithTag("compact-profile").performScrollToKey("profile-heading")
        rule.onAllNodesWithText("Neue Anzeige, 30").assertCountEquals(1)
        rule.onNodeWithText("Neuer Ort · Zuletzt bekannter Quellenstatus: Status unbekannt").assertExists()
        rule.onNodeWithText("Anzeigen: 2 · Veröffentlicht: 24.11.2026, 23:15 +02:00").assertExists()
        rule.onNodeWithText("Anzeigen: 1").assertDoesNotExist()
        rule.onNodeWithText("Fixture", substring = false).assertDoesNotExist()
        rule.onNodeWithText("Telefon").assertDoesNotExist()
        rule.onNodeWithText("WhatsApp").assertDoesNotExist()
        rule.runOnIdle { model = profile(snapshot = 13, phone = null) }
        rule.onNodeWithTag("compact-profile").performScrollToKey("profile-heading")
        rule.onNodeWithText("Neue Anzeige, 30").assertDoesNotExist()
        rule.onNodeWithText("Veröffentlicht:", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Telefon").assertDoesNotExist()
        rule.onNodeWithText("WhatsApp").assertDoesNotExist()
    }

    @Test fun confirmedFallbackAndRestoredGalleryUseTheSameSelectedMediumAndHeroBounds() {
        val fallback = ProfileMediaItem("IMAGE", "", null, 0u)
        val video = ProfileMediaItem("VIDEO", "", null, 1u)
        var model by mutableStateOf<ProfileReadModel?>(null)
        var selected by mutableStateOf<ProfileMediaItem?>(null)
        var receipt by mutableStateOf<SwipePresentation?>(null)
        var consumed by mutableIntStateOf(0)
        rule.setContent {
            LadiesTheme(darkTheme = false) {
                val list = rememberLazyListState()
                ProfileFlow(list, model, "{}", model?.let(::token), false, emptyMap(), notes,
                    { true }, { _, _ -> }, Modifier.fillMaxSize(), panelReads = reads { facts() },
                    fallbackMedia = listOf(fallback), onGallerySelection = { selected = it },
                    swipeRestore = receipt, swipeRunId = 3,
                    onSwipeRestoreConsumed = { consumed++; receipt = null }) {
                    item("hero") {
                        ProfileMediaHero(model?.media?.ifEmpty { listOf(fallback) } ?: listOf(fallback),
                            height = 180.dp, modifier = Modifier.testTag("restore-hero"), selection = mediaSelection,
                            projectionAvailable = model != null, actionToken = model?.let(::token),
                            actionsEnabled = false, isCurrent = { false }, compact = true)
                    }
                }
            }
        }
        rule.runOnIdle { assertNull(selected); model = profile(media = emptyList()) }
        rule.runOnIdle { assertEquals(fallback, selected) }
        val hero = rule.onNodeWithTag("restore-hero")
        val bounds = hero.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            model = profile(media = listOf(fallback, video))
            receipt = SwipePresentation(4, 1, 11, "{}", "VIDEO:", "hero", 0, emptyList(), 3, 1)
        }
        rule.waitUntil(5_000) { consumed == 1 }
        rule.onNodeWithContentDescription("Video abspielen").assertExists().assertIsNotEnabled()
        rule.runOnIdle { assertEquals(video, selected) }
        assertEquals(bounds, hero.fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithContentDescription("Vorheriges Medium").performClick()
        rule.runOnIdle { assertEquals(fallback, selected) }
        assertEquals(bounds, hero.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun searchMatchIsOpenAndShowsAlternativesWithoutASecondEvidenceList() {
        val original = profile()
        val source = ProfileFacts(emptyList(),
            listOf(ProfileField("Service", listOf("Fixture value"))), emptyList(),
            listOf(FilterMatchGroup("haarfarbe", EvidenceStatus.YES, listOf(
                FilterEvidence("haarfarbe", "blond", EvidenceStatus.YES),
                FilterEvidence("haarfarbe", "schwarz", EvidenceStatus.UNKNOWN))),
                FilterMatchGroup("radius", EvidenceStatus.UNKNOWN,
                    listOf(FilterEvidence("radius", "20 km", EvidenceStatus.UNKNOWN)))), emptyList())
        var context by mutableStateOf("run-1")
        val readers = ProfilePanelReads(
            { _, key -> if (key == "run-1") source else source.copy(filters = emptyList()) },
            { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) }, { _, _, _ -> emptyList() })
        rule.setContent {
            LadiesTheme(darkTheme = false) {
                ProfileFlow(rememberLazyListState(), original, context, token(original), false,
                    emptyMap(), notes, { true }, { _, _ -> },
                    Modifier.fillMaxWidth().height(320.dp).testTag("match-profile"), panelReads = readers) {
                    item("hero") { Text("Synthetic hero") }
                }
            }
        }
        val list = rule.onNodeWithTag("match-profile")
        list.performScrollToKey("profile-facts")
        rule.waitForIdle()
        rule.onNodeWithText("Übereinstimmung mit deiner Suche").assertExists()
        rule.onNodeWithText("1 von 2 belegt · 0 nicht erfüllt · 1 unbekannt").assertExists()
        rule.onNodeWithText("blond · Ja").assertExists()
        rule.onNodeWithText("schwarz · Unbekannt").assertExists()
        rule.onNodeWithText("Übereinstimmung mit deiner Suche").performClick()
        rule.onNodeWithText("blond · Ja").assertDoesNotExist()
        rule.onNodeWithText("Übereinstimmung mit deiner Suche").performClick()
        list.performScrollToKey("profile-services")
        rule.onNodeWithText("Dienste").performClick()
        rule.onNodeWithText("Filterbelege").assertDoesNotExist()
        rule.runOnIdle { context = "run-2" }
        list.performScrollToKey("profile-facts")
        rule.waitForIdle()
        rule.onNodeWithText("Übereinstimmung mit deiner Suche").assertDoesNotExist()
    }

    @Test fun progressiveProfileShowsFullSourceGroupsOnlyOnExpansion() {
        val original = profile()
        val source = ProfileFacts(
            listOf(ProfileField("Fixture trait", listOf("Trait value"))),
            listOf(ProfileField("Service", listOf("Value 1", "Value 2")),
                ProfileField("Treffpunkt", listOf("Other value"))),
            listOf(ProfileField("Montag", listOf("Fixture time"))), emptyList(), emptyList())
        var calls = 0
        val readers = reads { calls++; source }
        lateinit var state: LazyListState
        rule.setContent {
            LadiesTheme(darkTheme = false) {
                state = rememberLazyListState()
                ProfileFlow(state, original, "{}", token(original), false, emptyMap(), notes,
                    { true }, { _, _ -> }, Modifier.fillMaxWidth().height(220.dp).testTag("progressive-profile"),
                    panelReads = readers) {
                    item("hero") { Text("Synthetic hero") }
                }
            }
        }
        val list = rule.onNodeWithTag("progressive-profile")
        list.performScrollToKey("profile-facts")
        rule.waitForIdle()
        list.performScrollToKey("profile-description")
        rule.onNodeWithText(original.profile.description).assertDoesNotExist()
        rule.onNodeWithText("Mehr anzeigen").performClick()
        rule.onNodeWithText(original.profile.description).assertExists()
        list.performScrollToKey("profile-description")
        rule.onNodeWithText("Weniger anzeigen").performClick()
        rule.onNodeWithText(original.profile.description).assertDoesNotExist()
        list.performScrollToKey("profile-opening-times")
        rule.onNodeWithText("Fixture time").assertDoesNotExist()
        rule.onNodeWithText("Öffnungszeiten").performClick()
        rule.onNodeWithText("Fixture time").assertExists()
        list.performScrollToKey("profile-traits")
        rule.onNodeWithText("Trait value").assertDoesNotExist()
        rule.onNodeWithText("Merkmale").performClick()
        rule.onNodeWithText("Trait value").assertExists()
        list.performScrollToKey("profile-services")
        rule.onNodeWithText("Value 1").assertDoesNotExist()
        rule.onNodeWithText("Dienste").performClick()
        rule.onAllNodesWithText("Service", substring = false).assertCountEquals(1)
        rule.onNodeWithText("Value 1").assertExists()
        rule.onNodeWithText("Value 2").assertExists()
        rule.onNodeWithText("Treffpunkt").assertExists()
        rule.onNodeWithText("Other value").assertExists()
        val first = rule.runOnIdle { state.firstVisibleItemIndex }
        rule.onNodeWithText("Dienste").performClick()
        rule.onNodeWithText("Value 1").assertDoesNotExist()
        rule.runOnIdle { assertEquals(first, state.firstVisibleItemIndex); assertEquals(1, calls) }
    }

    @Test fun lateUndoReceiptWaitsForRealRowsAndSurvivesSavedStateRecreation() {
        val tester = StateRestorationTester(rule)
        val ready = CompletableDeferred<ProfileFacts>()
        val readers = reads { ready.await() }
        val original = profile()
        var model by mutableStateOf<ProfileReadModel?>(null)
        var receipt by mutableStateOf<SwipePresentation?>(null)
        var consumed by mutableIntStateOf(0)
        lateinit var list: LazyListState
        lateinit var capture: SwipePresentationCapture
        lateinit var jump: () -> Unit
        tester.setContent {
            MaterialTheme {
                list = rememberLazyListState()
                capture = remember { SwipePresentationCapture() }
                val scope = rememberCoroutineScope()
                jump = { scope.launch { list.scrollToItem(0) } }
                ProfileFlow(list, model, "{}", model?.let(::token), model == null, emptyMap(), notes,
                    isProfileCurrent = { true }, onConfirmIdentity = { _, _ -> },
                    modifier = Modifier.size(300.dp, 300.dp), swipeRestore = receipt, swipeRunId = 3,
                    swipeCapture = capture, onSwipeRestoreConsumed = { consumed++; receipt = null },
                    panelReads = readers) {
                    item("hero") {
                        Column(Modifier.height(400.dp)) {
                            val index = mediaSelection.resolve(original.media, model != null)
                            Text("Medium $index")
                            TextButton(onClick = { mediaSelection.select(original.media, 0) }) { Text("Erstes Medium") }
                        }
                    }
                }
            }
        }
        val position = SwipePresentation(4, 1, 11, "{}", "VIDEO:https://example.invalid/fixture",
            "profile-services", 12, listOf("Services"), runId = 3, mediaIndex = 1)
        rule.runOnIdle { receipt = position }
        rule.runOnIdle { assertEquals(0, consumed); assertNull(capture.read()) }
        rule.runOnIdle { model = original }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(0, consumed); assertNotNull(receipt) }
        // Actual saveable recreation before the data dependency becomes available.
        tester.emulateSavedInstanceStateRestore()
        rule.runOnIdle { assertEquals(0, consumed); ready.complete(facts()) }
        rule.waitUntil(5_000) { consumed == 1 }
        rule.runOnIdle {
            val actual = requireNotNull(capture.read())
            assertEquals("profile-services", actual.anchorKey)
            assertEquals(12, actual.anchorOffset)
            assertEquals(1, actual.mediaIndex)
            assertTrue("Services" in actual.sections)
        }
        rule.runOnIdle { jump() }
        rule.onNodeWithText("Medium 1").assertExists()
        rule.onNodeWithText("Erstes Medium").performClick()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Medium 0").assertExists()
        rule.runOnIdle { assertEquals(1, consumed) }
    }

    @Test fun actualRailVisitUndoAndRepeatedSkipCarryPresentationAcrossRouteGap() {
        val original = profile()
        val readers = reads { facts() }
        val session = SwipeSession()
        val row = DiscoveryCard(listingId = 4, personId = 1, name = "Fixture", city = "Fixture",
            thumbnailUrl = null, localPath = null, description = null, preference = Preference.NONE,
            favorite = false, hasVideo = false, hasAiContent = false, isPremium = false, age = null)
        var native = Preference.NONE
        var writes = 0
        var model by mutableStateOf<ProfileReadModel?>(original)
        var cardVisible by mutableStateOf(true)
        var routeVisible by mutableStateOf(true)
        var receipt by mutableStateOf<SwipePresentation?>(null)
        var generation by mutableLongStateOf(1)
        lateinit var capture: SwipePresentationCapture
        lateinit var services: () -> Unit
        val write: suspend (Long, Long, Preference?, Preference) -> Preference = { listing, owner, expected, target ->
            check(listing == 4L && owner == 1L && native == expected)
            val previous = native
            native = target
            writes++
            previous
        }
        fun advance() {
            receipt = null
            cardVisible = false
            model = null
            generation++
        }
        rule.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                val scope = rememberCoroutineScope()
                if (routeVisible) holder.SaveableStateProvider("swipe") {
                    val deck = SwipeDeckToken(3, generation)
                    val card = SwipeCardToken(deck, 4, 1).takeIf { cardVisible }
                    val action = model?.let { token(it).copy(session = generation, preference = native) }
                    Column(Modifier.size(300.dp, 400.dp)) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if (cardVisible) {
                                val list = rememberLazyListState()
                                capture = remember { SwipePresentationCapture() }
                                services = { scope.launch { list.scrollToItem(3, 12) } }
                                ProfileFlow(list, model, "{}", action, action == null, emptyMap(), notes,
                                    { true }, { _, _ -> }, Modifier.fillMaxSize(), swipeRestore = receipt,
                                    swipeRunId = 3, swipeCapture = capture,
                                    onSwipeRestoreConsumed = { session.consumePresentation(it); receipt = null },
                                    panelReads = readers) {
                                    item("hero") {
                                        Column(Modifier.height(440.dp)) {
                                            Text("Medium ${mediaSelection.resolve(original.media, model != null)}")
                                            TextButton(onClick = { mediaSelection.select(original.media, 1) }) {
                                                Text("Nächstes Medium")
                                            }
                                        }
                                    }
                                }
                            } else Text("Deck beendet")
                        }
                        Box(Modifier.fillMaxWidth().height(48.dp)) {
                            val context = SwipeControlsContext(deck, card, action, true, true,
                                AvailabilityMode.All, true, session.last != null)
                            SwipeDecisionControls(context, if (cardVisible) "Fixture" else null,
                                onUndo = {
                                    scope.launch {
                                        val step = requireNotNull(session.last)
                                        undoSwipeStep(step, write, { 1 }, session::undo)
                                        receipt = session.restoredPresentation(row)
                                        generation++
                                        cardVisible = true
                                    }
                                },
                                onSkip = {
                                    val current = requireNotNull(card)
                                    session.visit(SwipeStep(4, 1,
                                        presentation = presentationForDecision(receipt, capture.read(), current)))
                                    advance()
                                },
                                onChoose = { preference ->
                                    val current = requireNotNull(card)
                                    val position = presentationForDecision(receipt, capture.read(), current)
                                    scope.launch {
                                        commitProfilePreference(requireNotNull(action), preference, write, session::visit, position)
                                        advance()
                                    }
                                })
                        }
                    }
                } else Text("Grid")
            }
        }
        rule.onNodeWithText("Nächstes Medium").performClick()
        rule.runOnIdle { services() }
        rule.onNodeWithText("Alle 25 Angaben anzeigen").performClick()
        rule.runOnIdle { services() }
        lateinit var saved: SwipePresentation
        rule.runOnIdle {
            saved = requireNotNull(capture.read())
            assertEquals("profile-services", saved.anchorKey)
            assertEquals(12, saved.anchorOffset)
            assertEquals(1, saved.mediaIndex)
            assertTrue("Services" in saved.sections)
        }
        rule.onNode(hasText("Gefällt") or hasContentDescription("Gefällt")).performClick()
        rule.onNodeWithText("Deck beendet").assertExists()
        rule.onNode(hasText("Rückgängig") or hasContentDescription("Rückgängig")).performClick()
        rule.runOnIdle { assertEquals(saved, receipt); assertNull(model) }
        // Skip must remain available with a failed/missing projection without replacing the receipt.
        rule.onNode(hasText("Später") or hasContentDescription("Später")).performClick()
        rule.onNodeWithText("Deck beendet").assertExists()
        rule.onNode(hasText("Rückgängig") or hasContentDescription("Rückgängig")).performClick()
        rule.runOnIdle { assertEquals(saved, receipt); model = original }
        rule.waitUntil(5_000) { receipt == null }
        rule.runOnIdle {
            assertEquals(saved.anchorKey, capture.read()?.anchorKey)
            assertEquals(saved.anchorOffset, capture.read()?.anchorOffset)
            assertEquals(saved.mediaId, capture.read()?.mediaId)
            assertEquals(Preference.NONE, native)
            assertEquals(2, writes)
            routeVisible = false
        }
        rule.onNodeWithText("Grid").assertExists()
        rule.runOnIdle { model = null; routeVisible = true }
        rule.onNodeWithText("Medium 0").assertExists()
        rule.runOnIdle { model = original }
        rule.waitUntil(5_000) {
            capture.read()?.let { it.anchorKey == saved.anchorKey && it.anchorOffset == saved.anchorOffset } == true
        }
        rule.runOnIdle {
            assertEquals(saved.mediaId, capture.read()?.mediaId)
            assertNull(receipt)
            assertEquals(2, writes)
        }
    }

    @Test fun changedSnapshotRestoresIndependentKeysAndFailedPanelReadUsesSafeRowStart() {
        val original = profile(12)
        var calls = 0
        val readers = reads { if (++calls == 1) error("Fixture read failure") else facts() }
        var receipt by mutableStateOf<SwipePresentation?>(SwipePresentation(4, 1, 11, "{}",
            "VIDEO:https://example.invalid/fixture", "profile-services", 900, listOf("Services"), 3, 1))
        lateinit var capture: SwipePresentationCapture
        rule.setContent {
            MaterialTheme {
                capture = remember { SwipePresentationCapture() }
                ProfileFlow(rememberLazyListState(), original, "{}", token(original), false,
                    emptyMap(), notes, { true }, { _, _ -> }, Modifier.size(300.dp, 180.dp),
                    swipeRestore = receipt, swipeRunId = 3, swipeCapture = capture,
                    onSwipeRestoreConsumed = { receipt = null }, panelReads = readers) {
                    item("hero") { Text("Fixture", Modifier.height(400.dp)) }
                }
            }
        }
        rule.waitUntil(5_000) { receipt == null }
        rule.runOnIdle {
            val actual = requireNotNull(capture.read())
            assertEquals("profile-services", actual.anchorKey)
            assertEquals(0, actual.anchorOffset)
            assertEquals("VIDEO:https://example.invalid/fixture", actual.mediaId)
        }
        rule.onAllNodesWithText("Neu laden").onFirst().performClick()
        rule.onNodeWithText("Value 1").assertExists()
        rule.onNodeWithText("Fixture read failure").assertDoesNotExist()
        rule.runOnIdle {
            assertNull(receipt)
            assertEquals("VIDEO:https://example.invalid/fixture", capture.read()?.mediaId)
        }
    }
}
