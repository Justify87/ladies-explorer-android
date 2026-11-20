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
        assertEquals(.5f, gallerySideFraction(320f))
        assertEquals(.5f, gallerySideFraction(240f))
        val side = gallerySideFraction(320f, true)
        assertTrue(320f * side >= 48f)
        assertTrue(320f * (1f - 2f * side) >= 48f)
        assertEquals(.33f, gallerySideFraction(300f, true), .01f)
    }

    @Test fun identityRetainsSourceStatusVideoAndExistingPreference() {
        assertEquals("Test", HeroIdentity("Test", place = "Ort").title)
        assertEquals("Status unbekannt · Ort", HeroIdentity("Test", place = "Ort", status = statusLabel(null)).compactSubtitle)
        assertEquals("Gefällt · Favorit · Video", HeroIdentity("Test", place = "Ort", hasVideo = true,
            preference = Preference.LIKE, favorite = true).decisions)
        assertEquals("Unbekannt", HeroIdentity("", "", "").title)
        assertEquals("Ort unbekannt", HeroIdentity("", "", "").subtitle)
    }
}
