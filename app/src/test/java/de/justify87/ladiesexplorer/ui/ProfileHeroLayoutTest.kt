package de.justify87.ladiesexplorer.ui

import org.junit.Assert.*
import org.junit.Test

class ProfileHeroLayoutTest {
    @Test fun normalPortraitUsesMeasuredBudgetRatherThanRestMultiplier() {
        for ((width, height) in listOf(390f to 844f, 430f to 932f, 390f to 780f, 600f to 900f)) {
            val viewport = height - 56f - 80f - 48f
            assertEquals(height * .70f, profileHeroHeight(width, height, viewport), .01f)
        }
    }

    @Test fun smallerWindowKeepsMinimumWhenMeasuredControlsAllowIt() {
        assertEquals(740f * .65f, profileHeroHeight(360f, 740f, 740f - 184f), .01f)
        assertEquals(510f, profileHeroHeight(390f, 780f, 510f), .01f)
    }

    @Test fun largeControlsImeAndLandscapeNeverOverflowViewport() {
        assertEquals(180f, profileHeroHeight(600f, 400f, 180f), .01f)
        assertEquals(0f, profileHeroHeight(390f, 0f, 0f), .01f)
        assertTrue(profileHeroHeight(390f, 780f, 350f) <= 350f)
    }

    @Test fun identityMovesOutsideHeroForLargeTypeOrSmallWindows() {
        assertFalse(useHeaderIdentity(390f, 844f, 1.3f))
        assertTrue(useHeaderIdentity(390f, 844f, 2f))
        assertTrue(useHeaderIdentity(300f, 700f, 1f))
        assertTrue(useHeaderIdentity(600f, 400f, 1f))
    }

    @Test fun onlyAnExecutedMatchingRunDisplaysItsAvailability() {
        assertEquals("Lokaler Bestand · Swipe",
            swipeContextLabel("Lokaler Bestand", false, true, AvailabilityMode.Now))
        assertEquals("Leipzig · Swipe · Heute",
            swipeContextLabel("Leipzig", true, true, AvailabilityMode.Today))
        assertEquals("Aktive Suche · Swipe · Suchlauf wird übernommen",
            swipeContextLabel("Aktive Suche", false, false, AvailabilityMode.Now))
    }

    @Test fun compactIdentityOnlyAfterHeroLeavesAndRestoresOnReturn() {
        assertFalse(heroHasLeftViewport(0, 0, 1, 0))
        assertTrue(heroHasLeftViewport(0, 0, 0, 0))
        assertTrue(heroHasLeftViewport(2, 0, null, 0))
        assertFalse(heroHasLeftViewport(0, 0, null, 0))
    }

    @Test fun identityDoesNotInventAgeOrAvailability() {
        assertEquals("Test", HeroIdentity("Test", place = "Ort").title)
        assertEquals("Ort · Status unbekannt", HeroIdentity("Test", place = "Ort", status = statusLabel(null)).subtitle)
        assertEquals("Status unbekannt · Ort", HeroIdentity("Test", place = "Ort", status = statusLabel(null)).compactSubtitle)
        assertEquals("Unbekannt", HeroIdentity("", "", "").title)
        assertEquals("Ort unbekannt", HeroIdentity("", "", "").subtitle)
    }
}
