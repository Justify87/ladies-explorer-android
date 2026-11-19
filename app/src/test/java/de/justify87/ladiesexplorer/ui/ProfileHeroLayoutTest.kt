package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import org.junit.Assert.*
import org.junit.Test

class ProfileHeroLayoutTest {
    @Test fun normalPortraitUsesSharedChromeBudget() {
        for ((width, height) in listOf(390f to 844f, 430f to 932f, 390f to 780f, 600f to 900f, 360f to 740f)) {
            val root = 80f
            val budget = profileShellBudget(width, height - root, root, 1f, false, true)
            assertEquals(height - root, budget.header + budget.rail + budget.notice + budget.body, .01f)
            val expected = height * if (width >= 390f) .70f else .65f
            assertEquals(expected, profileHeroHeight(width, height, budget.body), .01f)
            assertFalse(budget.compactGallery)
            assertTrue(budget.header <= height * .14f)
        }
    }

    @Test fun noticesAndRailCannotConsumeReservedBody() {
        for (height in listOf(112f, 140f, 200f, 239f, 240f, 320f, 480f, 760f)) {
            for (font in listOf(1f, 1.3f, 2f)) {
                val plan = profileShellBudget(360f, height, 80f, font, true, true)
                assertEquals(height, plan.header + plan.notice + plan.rail + plan.body, .01f)
                assertTrue(plan.body >= minOf(96f, height - plan.header))
                assertTrue(plan.notice == 0f || plan.notice == 48f)
                assertTrue(plan.rail == 0f || plan.rail == 48f)
                if (height < 240f) {
                    assertTrue(plan.railInMenu)
                    assertTrue(plan.noticeInHeader)
                }
            }
        }
    }

    @Test fun standaloneUsesSameBudgetWithoutDeckControls() {
        val plan = profileShellBudget(390f, 844f, 0f, 1f, true, false)
        assertEquals(0f, plan.rail, .01f)
        assertEquals(48f, plan.notice, .01f)
        assertEquals(740f, plan.body, .01f)
        assertEquals(844f * .70f, profileHeroHeight(390f, 844f, plan.body), .01f)
    }

    @Test fun smallWindowsAndLargeTextUseScrollableGalleryChrome() {
        assertTrue(profileShellBudget(300f, 700f, 80f, 1f, false, true).compactGallery)
        assertTrue(profileShellBudget(390f, 764f, 80f, 2f, false, true).compactGallery)
        assertTrue(profileShellBudget(600f, 250f, 80f, 1f, true, true).compactGallery)
        assertEquals(0f, profileHeroHeight(390f, 0f, 0f), .01f)
        assertEquals(180f, profileHeroHeight(600f, 400f, 180f), .01f)
    }

    @Test fun identityHandoffFollowsMeasuredOverlayAndReversesWithoutChangingBudget() {
        assertFalse(headerOwnsIdentity(0, 0, 546, 100, 0, 600))
        assertFalse(headerOwnsIdentity(0, -445, 101, 100, 0, 600))
        assertTrue(headerOwnsIdentity(0, -447, 99, 100, 0, 600))
        assertTrue(headerOwnsIdentity(1, null, null, 100, 0, 600))
        assertFalse(headerOwnsIdentity(0, -445, 101, 100, 0, 600))
        assertTrue(headerOwnsIdentity(0, 0, 546, 100, 0, 400))
        assertFalse(headerOwnsIdentity(0, null, null, 0, 0, 600))
    }

    @Test fun mediaZonesLeaveAnIndependentCenter() {
        val side = gallerySideFraction(320f)
        assertTrue(320f * side >= 48f)
        assertTrue(320f * (1f - 2f * side) >= 160f)
        assertEquals(0f, gallerySideFraction(300f), .01f)
    }

    @Test fun menuNeverRebindsAcrossDeckOwnerRevisionOrBusyChanges() {
        val deck = SwipeDeckToken(3L, 4L)
        val card = SwipeCardToken(deck, 10L, 20L)
        val token = SwipeActionToken(3L, 4L, 10L, 20L, 30L, 1L, Preference.LIKE, false, emptyList())
        val context = SwipeMenuContext(deck, card, token, true, true, true, AvailabilityMode.Now, false, true)
        assertTrue(swipeMenuCurrent(context, context.copy()))
        assertFalse(swipeMenuCurrent(null, context))
        assertFalse(swipeMenuCurrent(context, context.copy(deck = deck.copy(session = 5L))))
        assertFalse(swipeMenuCurrent(context, context.copy(card = card.copy(ownerId = 21L))))
        assertFalse(swipeMenuCurrent(context, context.copy(profile = token.copy(projectionRevision = 2L))))
        assertFalse(swipeMenuCurrent(context, context.copy(profile = token.copy(preference = Preference.NONE))))
        assertFalse(swipeMenuCurrent(context, context.copy(profile = null, deckEnabled = false)))
        assertFalse(swipeMenuCurrent(context, context.copy(availabilityEnabled = false)))
        assertFalse(swipeMenuCurrent(context, context.copy(matchingRun = false)))
    }

    @Test fun localSourceAndAvailabilityAreExplicitEvenInCompactContext() {
        assertEquals("Lokaler Bestand · Swipe", swipeContextLabel("Lokaler Bestand", false, true, AvailabilityMode.Now))
        assertEquals("Leipzig · Swipe · Heute", swipeContextLabel("Leipzig", true, true, AvailabilityMode.Today))
        assertEquals("Lokal · Swipe", swipeContextShort(false, true, AvailabilityMode.Now))
        assertEquals("Suche · Heute · Swipe", swipeContextShort(true, true, AvailabilityMode.Today))
        assertEquals("Swipe · Suchwechsel", swipeContextShort(true, false, AvailabilityMode.Now))
    }

    @Test fun identityRetainsSourceStatusVideoAndExistingPreference() {
        assertEquals("Test", HeroIdentity("Test", place = "Ort").title)
        assertEquals("Status unbekannt · Ort", HeroIdentity("Test", place = "Ort", status = statusLabel(null)).compactSubtitle)
        assertEquals("Gefällt · Favorit · Video", HeroIdentity("Test", place = "Ort", hasVideo = true,
            preference = Preference.LIKE, favorite = true).decisions)
        assertEquals("Unbekannt", HeroIdentity("", "", "").title)
        assertEquals("Ort unbekannt", HeroIdentity("", "", "").subtitle)
    }

    @Test fun photoRequestSupersedesPendingAnchorRecovery() {
        val memory = ProfileScrollMemory(ProfileScrollAnchor("profile-facts", 35), initialRecovery = true)
        memory.returnToHero("hero")
        assertFalse(memory.recovering)
        assertEquals(ProfileScrollAnchor("hero", 0), memory.anchor)
        assertNull(memory.target(listOf("hero", "profile-facts")))
    }
}
