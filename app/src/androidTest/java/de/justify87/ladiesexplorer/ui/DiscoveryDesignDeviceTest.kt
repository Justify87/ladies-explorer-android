package de.justify87.ladiesexplorer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.justify87.ladiesexplorer.core.*
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DiscoveryDesignDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun contextHeroAndRailHaveIndependentMeasuredBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File.createTempFile("synthetic-hero-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val medium = ProfileMediaItem("IMAGE", "", image.absolutePath, 0u)
            val base = ProfileResponse(listingId = 4, snapshotId = 11, name = "Fixture", city = "Fixture", description = "Fixture description", postalCode = null,
                phoneDisplay = "+49 170 1234567",
                publishedAt = null)
            fun fixture(phone: Boolean) = ProfileReadModel(NativeProfileProjection(
                base.copy(phoneDisplay = base.phoneDisplay.takeIf { phone }),
                ProfileIdentity(personId = 1, status = "ACTIVE", profileUrl = null, personNew = false,
                    listingNew = false, hasVideo = false, age = null),
                PersonSummary(1, Preference.NONE, false, 1u),
                listOf(medium), emptyList(), emptyList()), true)
            val notes = NoteEditorActions({ _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, { _, _ -> })
            val reads = ProfilePanelReads({ _, _ -> ProfileFacts(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()) },
                { PersonDetail(emptyList(), 0u, 0u, 0u, 0u) }, { _, _, _ -> emptyList() })
            var model by mutableStateOf(fixture(true))
            var dark by mutableStateOf(false)
            var visual by mutableStateOf<HeroVisual?>(null)
            var pxPerDp = 1f
            lateinit var list: androidx.compose.foundation.lazy.LazyListState
            rule.setContent {
                LadiesTheme(darkTheme = dark) {
                    pxPerDp = LocalDensity.current.density
                    list = rememberLazyListState()
                    val deck = SwipeDeckToken(null, 1)
                    val token = SwipeActionToken(null, 1, 4, 1, 11, model.revision, Preference.NONE, false, emptyList())
                    val controls = SwipeControlsContext(deck, SwipeCardToken(deck, 4, 1), token,
                        true, true, AvailabilityMode.All, false, true)
                    Column(Modifier.fillMaxSize()) {
                        ProfileShell(Modifier.weight(1f), list, "hero",
                            hasRail = true, notices = emptyList(), noticeOwner = controls,
                            rootNavigation = {
                                Box(Modifier.fillMaxWidth().testTag("root-nav")) {
                                    RootNavigation(RootDestination.Discover, true, false, {})
                                }
                            },
                            heroLeadingMeasure = {
                                Box(Modifier.fillMaxWidth().padding(start = shellContentInset(),
                                    end = shellContentInset(), bottom = ShellMetrics.inset)) {
                                    DiscoverModeSwitcher(DiscoverMode.Swipe, {}, Modifier)
                                }
                            },
                            header = { _, _, _ -> Row(Modifier.fillMaxSize().testTag("header")) {
                                Text("Entdecken", Modifier.weight(1f))
                                AvailabilityQuickChoice(AvailabilityMode.All, true, deck, {})
                            } },
                            rail = { presentation ->
                                Box(Modifier.fillMaxSize().testTag("rail")) {
                                    SwipeDecisionControls(controls, "Fixture", {}, {}, {}, reading = presentation.reading)
                                }
                            }) { presentation ->
                            ProfileFlow(list, model, "{}", token, false, emptyMap(), notes, { true }, { _, _ -> },
                                Modifier.fillMaxSize().testTag("profile-flow"), paddedDetails = true, panelReads = reads,
                                contentPadding = PaddingValues(bottom = presentation.readingExtraSpace)) {
                                item("discover-context") {
                                    Box(Modifier.testTag("context")) {
                                        DiscoverModeSwitcher(DiscoverMode.Swipe, {}, Modifier)
                                    }
                                }
                                item("hero") {
                                    Box(Modifier.fillMaxWidth().padding(horizontal = shellContentInset())) {
                                        ProfileMediaHero(model.media, presentation.heroHeight, Modifier.testTag("hero"),
                                            actionsEnabled = true, isCurrent = { true }, selection = mediaSelection,
                                            favorite = false, onFavorite = {}, actionToken = token,
                                            compact = presentation.budget.compactGallery, onVisual = { visual = it })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { visual != null }
            val header = rule.onNodeWithTag("header").fetchSemanticsNode().boundsInRoot
            val contextBounds = rule.onNodeWithTag("context").fetchSemanticsNode().boundsInRoot
            val hero = rule.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot
            val rail = rule.onNodeWithTag("rail").fetchSemanticsNode().boundsInRoot
            val nav = rule.onNodeWithTag("root-nav").fetchSemanticsNode().boundsInRoot
            assertTrue(contextBounds.top >= header.bottom - 1f)
            assertTrue(hero.top >= contextBounds.bottom - 1f)
            assertTrue(hero.height > 0f)
            assertEquals(rail.top, hero.bottom, 1f)
            assertTrue(rail.bottom <= nav.top + 1f)
            rule.runOnIdle {
                val layout = list.layoutInfo
                val contact = layout.visibleItemsInfo.firstOrNull { it.key == "profile-contact" }
                assertTrue(contact == null || contact.offset >= layout.viewportEndOffset)
            }
            val favorite = rule.onNodeWithContentDescription("Als Favorit speichern").fetchSemanticsNode().boundsInRoot
            val measured = checkNotNull(visual).geometry
            val expected = measured.favoriteOrigin((48 * pxPerDp).toInt(), (6 * pxPerDp).toInt())
            assertTrue(favorite.width >= 48 * pxPerDp - 1f && favorite.height >= 48 * pxPerDp - 1f)
            assertEquals(hero.left + expected.x, favorite.left, 2f)
            assertEquals(hero.top + expected.y, favorite.top, 2f)
            val decisions = listOf("Rückgängig", "Nein", "Später", "Gefällt").map { label ->
                rule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            }
            decisions.forEach { bounds ->
                assertTrue(bounds.width >= 48 * pxPerDp - 1f)
                assertTrue(bounds.height >= 48 * pxPerDp - 1f)
            }
            decisions.zipWithNext().forEach { (left, right) -> assertTrue(left.right <= right.left + 1f) }
            val flow = rule.onNodeWithTag("profile-flow")
            flow.performScrollToKey("profile-contact")
            rule.onNodeWithText("Telefon").assertIsDisplayed()
            rule.onNodeWithText("WhatsApp").assertIsDisplayed()
            flow.performScrollToKey("discover-context")
            rule.runOnIdle { model = fixture(false); dark = true }
            rule.waitUntil(10_000) { visual != null }
            rule.runOnIdle {
                val layout = list.layoutInfo
                assertTrue(layout.visibleItemsInfo.none { it.key == "profile-contact" })
                val heading = layout.visibleItemsInfo.firstOrNull { it.key == "profile-heading" }
                assertTrue(heading == null || heading.offset >= layout.viewportEndOffset)
            }
            assertEquals(hero, rule.onNodeWithTag("hero").fetchSemanticsNode().boundsInRoot)
        } finally {
            image.delete()
        }
    }

    @Test fun gridHorizontalDecisionsTapAndVerticalScrollAreExclusive() {
        val actions = mutableListOf<String>()
        lateinit var scroll: androidx.compose.foundation.lazy.LazyListState
        var preference by mutableStateOf(Preference.NONE)
        var owner by mutableIntStateOf(1)
        rule.setContent {
            MaterialTheme {
                scroll = rememberLazyListState()
                LazyColumn(Modifier.width(320.dp).height(320.dp), state = scroll) {
                    item {
                        ProfileGridCard("Fixture", "30", null, null, true,
                            onOpen = { actions.add("open") },
                            modifier = Modifier.testTag("card").width(240.dp), place = "Ort",
                            preference = preference, decisionOwner = owner,
                            onDecision = { actions.add(it.name); preference = it })
                    }
                    items(20) { Text("Detail", Modifier.height(60.dp)) }
                }
            }
        }
        val card = rule.onNodeWithTag("card")
        rule.onNodeWithText("Fixture").assertDoesNotExist()
        rule.onNodeWithText("Ort").assertDoesNotExist()
        rule.onNodeWithText("30").assertExists()
        card.performTouchInput { click(center) }
        card.performTouchInput { swipe(Offset(width * .15f, center.y), Offset(width * .85f, center.y)) }
        card.performTouchInput { swipe(Offset(width * .85f, center.y), Offset(width * .15f, center.y)) }
        card.performTouchInput { down(center); moveTo(center + Offset(width * .1f, 0f)); up() }
        card.performTouchInput { down(center); moveTo(center + Offset(width * .4f, 0f)); cancel() }
        card.performTouchInput { down(Offset(width * .15f, center.y)); moveTo(Offset(width * .85f, center.y)) }
        rule.runOnIdle { owner++ }
        card.performTouchInput { up() }
        rule.runOnIdle { assertEquals(listOf("open", "LIKE", "DISLIKE"), actions) }
        rule.onNodeWithContentDescription("Fixture", substring = true).assertExists()
        card.performTouchInput { swipe(Offset(center.x, height * .8f), Offset(center.x, height * .1f)) }
        rule.runOnIdle {
            assertEquals(listOf("open", "LIKE", "DISLIKE"), actions)
            assertTrue(scroll.firstVisibleItemIndex > 0 || scroll.firstVisibleItemScrollOffset > 0)
        }
    }

    @Test fun gridDistinguishesUnknownFromConfirmedEmptyAfterLocalProjectionFailure() {
        val run = SearchResponse(
            query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}"),
            local = defaultLocalVisibility(), searchRunId = 7L, city = "Leipzig",
            requestedRadiusKm = 20u, serverCount = 0u, totalPages = 1u, fetchedPages = 1u,
            partial = false, radiusBoundaryStopped = false, radiusVerified = true,
        )
        var discovery by mutableStateOf(DiscoverUiState(activeRunId = 7L, loading = false,
            error = "Synthetischer Lesefehler"))
        var search by mutableStateOf(SearchUiState().acceptRun(run, emptyList())
            .copy(activeProjectionError = "Synthetischer Lesefehler"))
        rule.setContent {
            LadiesTheme {
                DiscoveryGridScreen(discovery, search, onLoadMore = {},
                    onDecision = { _, _ -> }, onUndo = {}, onProfile = {}, chrome = {},
                    onCancelSearch = {}, modifier = Modifier.width(360.dp).height(480.dp))
            }
        }
        rule.onNodeWithText("Lokale Treffer unbekannt.").assertExists()
        rule.onNodeWithText("Keine Treffer.").assertDoesNotExist()
        rule.runOnIdle {
            discovery = discovery.copy(error = null)
            search = search.copy(activeProjectionPending = true, activeProjectionError = null)
        }
        rule.onNodeWithText("Keine Treffer.").assertDoesNotExist()
        rule.onNodeWithText("Lokale Treffer unbekannt.").assertDoesNotExist()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertExists()
        rule.runOnIdle { search = search.acceptRun(run, emptyList()) }
        rule.onNodeWithText("Keine Treffer.").assertExists()
    }

    @Test fun gridUndoUsesCurrentReceiptAndRemainsAvailableWhenLocalCardDisappears() {
        val receipt = GridUndoReceipt(null, 11L, 12L, Preference.NONE, Preference.LIKE)
        var state by mutableStateOf(DiscoverUiState(loading = false, actionSurface = ProfileActionSurface.GRID,
            actionGeneration = 5, gridUndo = receipt))
        val undos = mutableListOf<GridUndoAction>()
        rule.setContent {
            LadiesTheme {
                DiscoveryGridScreen(state, SearchUiState(), onLoadMore = {},
                    onDecision = { _, _ -> }, onUndo = { undos.add(it); state = state.copy(gridUndo = null) },
                    onProfile = {}, chrome = {}, onCancelSearch = {},
                    modifier = Modifier.width(360.dp).height(480.dp))
            }
        }
        val undo = rule.onNodeWithText("Letzte Entscheidung rückgängig")
        rule.onNodeWithText("Keine Treffer.").assertExists()
        rule.runOnIdle { state = state.copy(busy = true) }
        undo.assertIsNotEnabled()
        rule.runOnIdle { state = state.copy(busy = false, actionGeneration = 6) }
        undo.performClick()
        rule.runOnIdle { assertEquals(listOf(GridUndoAction(receipt, 6)), undos) }
        undo.assertDoesNotExist()
    }

    @Test fun quickChoiceKeepsRunOwnerAndAllOptionsAcrossModesAndThemes() {
        var owner by mutableIntStateOf(1)
        var dark by mutableStateOf(true)
        var mode by mutableStateOf(DiscoverMode.Swipe)
        var availability by mutableStateOf(AvailabilityMode.All)
        val choices = mutableListOf<AvailabilityMode>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                LadiesTheme(darkTheme = dark) {
                    Column(Modifier.width(360.dp)) {
                        Row {
                            Text("Entdecken", Modifier.weight(1f))
                            AvailabilityQuickChoice(availability, true, owner, {
                                choices.add(it)
                                availability = it
                            })
                        }
                        DiscoverModeSwitcher(mode, { mode = it }, Modifier)
                    }
                }
            }
        }
        val initial = rule.onNodeWithText("Alle ▾").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Alle ▾").performClick()
        rule.onNodeWithText("Heute").performClick()
        val changed = rule.onNodeWithText("Heute ▾").fetchSemanticsNode().boundsInRoot
        assertEquals(initial.left, changed.left, 1f)
        assertEquals(initial.right, changed.right, 1f)
        rule.onNodeWithText("Heute ▾").performClick()
        rule.runOnIdle { owner++ }
        rule.onNodeWithText("Jetzt").assertDoesNotExist()
        rule.runOnIdle { dark = false }
        rule.onNodeWithText(DiscoverMode.Grid.title).performClick().assertIsSelected()
        rule.onNodeWithText("Karte").performClick().assertIsSelected()
        rule.onNodeWithText("Heute ▾").performClick()
        rule.onNodeWithText("Jetzt").performClick()
        rule.runOnIdle { assertEquals(listOf(AvailabilityMode.Today, AvailabilityMode.Now), choices) }
    }

    @Test fun narrowInlineRailWrapsWithoutLosingActions() {
        val actions = mutableListOf<String>()
        rule.setContent {
            LadiesTheme {
                val deck = SwipeDeckToken(null, 1)
                val context = SwipeControlsContext(deck, SwipeCardToken(deck, 4, 1),
                    SwipeActionToken(null, 1, 4, 1, 11, 1, Preference.NONE, false, emptyList()),
                    true, true, AvailabilityMode.All, false, true)
                LazyColumn(Modifier.width(168.dp).height(200.dp)) {
                    item {
                        SwipeDecisionControls(context, "Fixture", { actions.add("undo") },
                            { actions.add("skip") }, { actions.add(it.name) },
                            Modifier.fillMaxWidth().heightIn(min = ShellMetrics.touch), horizontalInset = 0.dp)
                    }
                }
            }
        }
        val labels = listOf("Rückgängig", "Nein", "Später", "Gefällt")
        val nodes = labels.map { rule.onNodeWithContentDescription(it) }
        val bounds = nodes.map { it.fetchSemanticsNode().boundsInRoot }
        val minimum = with(rule.density) { ShellMetrics.touch.toPx() } - 1f
        bounds.forEach { assertTrue(it.width >= minimum && it.height >= minimum) }
        assertTrue(bounds.last().top >= bounds.first().bottom - 1f)
        nodes.forEach { it.assertIsDisplayed().performClick() }
        rule.runOnIdle { assertEquals(listOf("undo", "DISLIKE", "skip", "LIKE"), actions) }
    }

    @Test fun shortGridScrollsPastChromeAndBackToModeSelection() {
        val actions = mutableListOf<DiscoverMode>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                LadiesTheme(darkTheme = true) {
                    DiscoveryGridScreen(
                        discovery = DiscoverUiState(loading = false),
                        search = SearchUiState(error = "Synthetischer Suchstatus"),
                        onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {}, onProfile = {},
                        chrome = {
                            DiscoverModeSwitcher(DiscoverMode.Grid, { actions.add(it) }, Modifier)
                        },
                        onCancelSearch = {},
                        modifier = Modifier.width(360.dp).height(150.dp),
                    )
                }
            }
        }
        val grid = rule.onNode(hasScrollToIndexAction())
        grid.performScrollToIndex(1)
        rule.onNodeWithText("Synthetischer Suchstatus").assertIsDisplayed()
        grid.performScrollToIndex(2)
        rule.onNodeWithText("Keine Treffer.").assertIsDisplayed()
        grid.performScrollToIndex(0)
        rule.onNodeWithText(DiscoverMode.Swipe.title).performClick()
        rule.runOnIdle { assertEquals(listOf(DiscoverMode.Swipe), actions) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun visibleDislikeDimsOnlyConfirmedPreferenceAndRestoresNeutral() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File.createTempFile("synthetic-grid-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            var preference by mutableStateOf(Preference.NONE)
            var owner by mutableIntStateOf(1)
            var requested by mutableStateOf<Preference?>(null)
            rule.setContent {
                LadiesTheme {
                    ProfileGridCard("Fixture", "30", null, image.absolutePath, true, {},
                        modifier = Modifier.width(160.dp).testTag("dim-card"),
                        preference = preference, decisionOwner = owner,
                        onDecision = { requested = it })
                }
            }
            val card = rule.onNodeWithTag("dim-card")
            fun centerColor(): androidx.compose.ui.graphics.Color {
                val pixels = card.captureToImage().toPixelMap()
                return pixels[pixels.width / 2, pixels.height / 2]
            }
            fun centerBlue() = centerColor().blue
            rule.waitUntil(10_000) { centerColor().let { it.blue > .95f && it.red < .05f && it.green < .05f } }
            val original = centerBlue()
            val action = rule.onNodeWithContentDescription("Fixture").fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].single { it.label == "Gefällt nicht" }
            rule.runOnIdle { action.action(); owner++ }
            assertEquals(Preference.DISLIKE, requested)
            assertEquals(original, centerBlue(), .05f)
            rule.runOnIdle { preference = Preference.DISLIKE; owner++ }
            assertTrue(centerBlue() < original - .15f)
            rule.runOnIdle { preference = Preference.LIKE; owner++ }
            assertEquals(original, centerBlue(), .05f)
            rule.runOnIdle { preference = Preference.NONE; owner++ }
            assertEquals(original, centerBlue(), .05f)
        } finally {
            image.delete()
        }
    }

    @Test fun gridMembershipChangeAnimatesPlacementAndUndoRestoresListing() {
        val initial = (1L..6L).map { id ->
            DiscoveryCard(id, id, "Fixture $id", "Ort", null, null, null, Preference.NONE,
                false, false, false, false, null)
        }
        var rows by mutableStateOf(initial)
        rule.setContent {
            LadiesTheme {
                DiscoveryGridScreen(
                    discovery = DiscoverUiState(localCards = rows, loading = false),
                    search = SearchUiState(), onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {},
                    onProfile = {}, chrome = {}, onCancelSearch = {},
                    modifier = Modifier.width(360.dp).height(480.dp),
                )
            }
        }
        val moved = rule.onNodeWithContentDescription("Fixture 3")
        val first = rule.onNodeWithContentDescription("Fixture 1")
        val original = moved.fetchSemanticsNode().boundsInRoot.top
        val destination = first.fetchSemanticsNode().boundsInRoot.top
        assertTrue(original > destination)
        rule.mainClock.autoAdvance = false
        try {
            rule.runOnIdle { rows = initial.filterNot { it.listingId == 2L } }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(64)
            val intermediate = moved.fetchSemanticsNode().boundsInRoot.top
            assertTrue(intermediate > destination && intermediate < original)
            rule.mainClock.advanceTimeBy(1000)
            assertEquals(destination, moved.fetchSemanticsNode().boundsInRoot.top, 2f)
            rule.runOnIdle { rows = initial }
            rule.mainClock.advanceTimeBy(1000)
            assertEquals(original, moved.fetchSemanticsNode().boundsInRoot.top, 2f)
            rule.onNodeWithContentDescription("Fixture 2").assertIsDisplayed()
        } finally {
            rule.mainClock.autoAdvance = true
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun lastGridMemberFadesOutAndBackWithoutAnInteractiveOutgoingCard() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File.createTempFile("synthetic-fade-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val initial = (1L..4L).map { id ->
                DiscoveryCard(id, id, "Fixture $id", "Ort", null, null,
                    image.absolutePath.takeIf { id == 4L }, Preference.NONE,
                    false, false, false, false, null)
            }
            var rows by mutableStateOf(initial)
            rule.setContent {
                LadiesTheme(darkTheme = true) {
                    DiscoveryGridScreen(DiscoverUiState(localCards = rows, loading = false), SearchUiState(),
                        onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {}, onProfile = {},
                        chrome = {}, onCancelSearch = {},
                        modifier = Modifier.width(360.dp).height(480.dp).testTag("fade-grid"))
                }
            }
            val grid = rule.onNodeWithTag("fade-grid")
            val outgoing = rule.onNodeWithContentDescription("Fixture 4")
            val position = outgoing.fetchSemanticsNode().boundsInRoot.center -
                grid.fetchSemanticsNode().boundsInRoot.topLeft
            fun redSignal(): Float {
                val pixels = grid.captureToImage().toPixelMap()
                val color = pixels[position.x.toInt(), position.y.toInt()]
                return color.red - color.green
            }
            rule.waitUntil(10_000) { redSignal() > .9f }
            val full = redSignal()
            rule.mainClock.autoAdvance = false
            try {
                rule.runOnIdle { rows = initial.dropLast(1) }
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeBy(64)
                outgoing.assertDoesNotExist()
                val fading = redSignal()
                rule.mainClock.advanceTimeBy(1000)
                val absent = redSignal()
                assertTrue(fading > absent + .05f && fading < full - .05f)
                rule.runOnIdle { rows = initial }
                rule.mainClock.advanceTimeByFrame()
                rule.mainClock.advanceTimeBy(64)
                val appearing = redSignal()
                rule.mainClock.advanceTimeBy(1000)
                assertTrue(appearing < full - .05f)
            } finally {
                rule.mainClock.autoAdvance = true
            }
            rule.waitUntil(10_000) { redSignal() > .9f }
            outgoing.assertIsDisplayed()
        } finally {
            image.delete()
        }
    }

    @Test fun gridProjectionPendingAndFailureDoNotReplaceValidScrollAnchor() {
        val rows = (1L..24L).map { id ->
            DiscoveryCard(id, id, "Fixture $id", "Ort", null, null, null, Preference.NONE,
                false, false, false, false, null)
        }
        val run = SearchResponse(
            query = SearchRequest("Leipzig", 20u, "", 18u, 60u, 140u, 200u, "{}"),
            local = defaultLocalVisibility(), searchRunId = 7L, city = "Leipzig",
            requestedRadiusKm = 20u, serverCount = 24u, totalPages = 1u, fetchedPages = 1u,
            partial = false, radiusBoundaryStopped = false, radiusVerified = true,
        )
        var search by mutableStateOf(SearchUiState().acceptRun(run, rows))
        val discovery = DiscoverUiState(activeRunId = 7L, loading = false)
        rule.setContent {
            LadiesTheme {
                DiscoveryGridScreen(discovery, search, onLoadMore = {}, onDecision = { _, _ -> },
                    onUndo = {}, onProfile = {}, chrome = {}, onCancelSearch = {},
                    modifier = Modifier.width(360.dp).height(480.dp))
            }
        }
        val grid = rule.onNode(hasScrollToIndexAction())
        grid.performScrollToKey(9L)
        val profile = rule.onNodeWithContentDescription("Fixture 9")
        val top = profile.fetchSemanticsNode().boundsInRoot.top
        rule.runOnIdle { search = search.copy(activeProjectionPending = true) }
        profile.assertDoesNotExist()
        rule.runOnIdle { search = search.copy(activeCards = emptyList(), activeProjectionPending = false,
            activeProjectionError = "Synthetischer Lesefehler") }
        profile.assertDoesNotExist()
        rule.runOnIdle { search = search.acceptRun(run, rows) }
        profile.assertIsDisplayed()
        assertEquals(top, profile.fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun gridReturnKeepsVisibleListingWhenEarlierMembersDisappear() {
        var route by mutableStateOf(ShellRoute().select(DiscoverMode.Grid))
        var rows by mutableStateOf((1L..24L).map { id ->
            DiscoveryCard(id, id, "Fixture $id", "Ort", null, null, null, Preference.NONE,
                false, false, false, false, null)
        })
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            LadiesTheme {
                holder.SaveableStateProvider(route.stateKey) {
                    if (route.discovery == DiscoverMode.Grid) {
                        DiscoveryGridScreen(
                            discovery = DiscoverUiState(localCards = rows, loading = false),
                            search = SearchUiState(), onLoadMore = {}, onDecision = { _, _ -> }, onUndo = {},
                            onProfile = { route = route.openSwipe() },
                            chrome = {}, onCancelSearch = {},
                            modifier = Modifier.width(360.dp).height(480.dp),
                        )
                    } else Text("Swipe")
                }
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(7)
        val profile = rule.onNodeWithContentDescription("Fixture 8")
        profile.assertIsDisplayed()
        val top = profile.fetchSemanticsNode().boundsInRoot.top
        profile.performClick()
        rule.onNodeWithText("Swipe").assertIsDisplayed()
        rule.runOnIdle {
            rows = rows.drop(2)
            route = route.back()
        }
        profile.assertIsDisplayed()
        assertEquals(top, profile.fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test fun compactGridStatusAndAccessibleActionsAcrossThemesAndFontScale() {
        val name = "Sehr langer synthetischer Profilname"
        var font by mutableFloatStateOf(1.3f)
        var dark by mutableStateOf(true)
        var preference by mutableStateOf(Preference.NONE)
        val decisions = mutableListOf<Preference>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                LadiesTheme(darkTheme = dark) {
                    ProfileGridCard(name, "30", null, null, true, {},
                        modifier = Modifier.width(160.dp).testTag("compact-card"),
                        preference = preference, decisionOwner = preference,
                        onDecision = { decisions.add(it); preference = it })
                }
            }
        }
        for (scale in listOf(1.3f, 2f)) {
            rule.runOnIdle { font = scale; dark = scale == 1.3f }
            val card = rule.onNodeWithContentDescription(name)
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= bounds.width / 1.03f - 1f)
            rule.onNodeWithText(name).assertDoesNotExist()
            rule.onNodeWithText("30").assertExists()
            for ((label, value) in listOf("Gefällt" to Preference.LIKE, "Gefällt nicht" to Preference.DISLIKE,
                "Bewertung entfernen" to Preference.NONE)) {
                val action = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == label }
                rule.runOnIdle { assertTrue(action.action()) }
                card.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, gridPreferenceLabel(value)))
            }
        }
        rule.runOnIdle { assertEquals(listOf(Preference.LIKE, Preference.DISLIKE, Preference.NONE,
            Preference.LIKE, Preference.DISLIKE, Preference.NONE), decisions) }
    }
}
