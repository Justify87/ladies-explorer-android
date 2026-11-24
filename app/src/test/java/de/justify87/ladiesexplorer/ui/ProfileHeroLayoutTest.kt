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
            val hero = profileHeroHeight(width, height, budget.body, discoveryLeadingHeight(1f))
            assertTrue(hero > 250f)
            assertTrue(hero <= height * .76f + .01f)
            val previousHero = minOf(height * .63f, width * 1.55f, budget.body - 116f - 24f)
            assertTrue("More room for the hero", hero > previousHero)
            assertTrue(hero + discoveryLeadingHeight(1f) + 24f <= budget.body + .01f)
            assertEquals(80f, budget.rail, .01f)
            assertFalse(budget.compactGallery)
        }
    }

    @Test fun noticesAndRailCannotConsumeReservedBody() {
        for (height in listOf(112f, 140f, 200f, 239f, 240f, 320f, 480f, 760f)) {
            for (font in listOf(1f, 1.3f, 2f)) {
                val plan = profileShellBudget(360f, height, 80f, font, true, true)
                assertEquals(height, plan.header + plan.notice + plan.rail + plan.body, .01f)
                assertTrue(plan.body >= minOf(96f, height - plan.header))
                assertTrue(plan.notice == 0f || plan.notice == 48f)
                assertTrue(plan.rail in setOf(0f, 48f, 80f))
                if (height < 240f) {
                    assertTrue(plan.railInContent)
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
        assertEquals(844f * .63f, profileHeroHeight(390f, 844f, plan.body), .01f)
    }

    @Test fun smallWindowsAndLargeTextUseScrollableGalleryChrome() {
        assertTrue(profileShellBudget(300f, 700f, 80f, 1f, false, true).compactGallery)
        assertTrue(profileShellBudget(390f, 764f, 80f, 2f, false, true).compactGallery)
        assertTrue(profileShellBudget(600f, 250f, 80f, 1f, true, true).compactGallery)
        assertEquals(0f, profileHeroHeight(390f, 0f, 0f), .01f)
        assertEquals(180f, profileHeroHeight(600f, 400f, 180f), .01f)
    }

    @Test fun inlineIdentityUsesCanonicalSnapshotPlaceNotHiddenSourceMetadata() {
        val profile = fixtureProfile().copy(city = "Dresden", postalCode = "01067",
            hiddenLocation = "Offenbach am Main")
        val identity = fixtureIdentity(1).copy(age = "29")
        val shown = heroIdentity(profile, identity, false)
        assertEquals("Fixture, 29", shown.title)
        assertEquals("Dresden · 01067", shown.place)
        assertEquals("Dresden · 01067 · Anzeige aktiv", shown.subtitle)
        assertEquals("", heroIdentity(profile.copy(city = "", postalCode = null), identity, true).place)
        assertEquals("Zuletzt bekannt: Anzeige aktiv", heroIdentity(profile, identity, true).status)
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

    @Test fun responsiveBudgetAcrossWidthsFontScalesInsetsAndShortWindows() {
        for ((width, height) in listOf(360f to 740f, 390f to 844f, 430f to 932f, 600f to 900f, 740f to 360f)) {
            for (font in listOf(1f, 1.3f, 2f)) for (system in listOf(0f, 48f, 80f)) {
                for (ime in listOf(0f, 220f, 320f)) for (notice in listOf(false, true)) {
                    for (rail in listOf(false, true)) {
                        val root = if (rail) 64f * font else 0f
                        val usable = (height - system - ime - root).coerceAtLeast(0f)
                        val budget = profileShellBudget(width, usable, root, font, notice, rail)
                        val hero = profileHeroHeight(width, usable + root, budget.body,
                            if (rail) discoveryLeadingHeight(font) else 0f)
                        val context = "width=$width, height=$height, font=$font, system=$system, ime=$ime, rail=$rail"
                        assertEquals(context, usable, budget.header + budget.notice + budget.rail + budget.body, .01f)
                        assertTrue(context, budget.body >= minOf(96f, usable - budget.header) - .01f)
                        assertTrue(context, hero >= 0f && hero <= budget.body + .01f)
                        assertTrue(context, !rail || budget.railInContent || budget.rail >= 48f)
                        assertTrue(context, rail || budget.railInContent)
                        assertTrue(context, !notice || budget.noticeInHeader || budget.notice >= 48f)
                        if (usable >= 144f) assertTrue(context, budget.header >= 48f && budget.body > 0f)
                    }
                }
            }
        }
    }
}
