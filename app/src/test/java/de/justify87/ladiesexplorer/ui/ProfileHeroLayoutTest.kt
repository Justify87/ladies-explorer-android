package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.Preference
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class ProfileHeroLayoutTest {
    @Test fun normalPortraitUsesSharedChromeBudget() {
        for ((width, height) in listOf(390f to 844f, 430f to 932f, 390f to 780f, 600f to 900f, 360f to 740f)) {
            val root = 80f
            val budget = profileShellBudget(width, height - root, root, 1f, false, true)
            assertEquals(height - root, budget.header + budget.rail + budget.notice + budget.body, .01f)
            val measuredContext = 60f
            val hero = profileHeroHeight(width, height, budget.body, measuredContext)
            assertTrue(hero > 250f)
            assertEquals(budget.body - measuredContext, hero, .01f)
            val previousHero = minOf(height * .76f, width * 1.85f, budget.body - measuredContext - 24f)
            assertTrue("More room for the hero", hero > previousHero)
            assertEquals(budget.body, hero + measuredContext, .01f)
            assertEquals(ShellMetrics.decisionRail.value, budget.rail, .01f)
            assertFalse(budget.compactGallery)
        }
    }

    @Test fun initialHeroWaitsForMeasuredContextButRestoredRowsNeverDependOnIt() {
        assertTrue(awaitDiscoveryContext(0, 0))
        assertFalse(awaitDiscoveryContext(60, 0))
        assertFalse(awaitDiscoveryContext(0, 1))
        assertFalse(awaitDiscoveryContext(0, 4))
    }

    @Test fun measuredContextAndShortViewportHaveNoIntentionalPeek() {
        for (context in listOf(48f, 60f, 76f, 108f)) {
            assertEquals(500f - context, profileHeroHeight(390f, 844f, 500f, context), .01f)
        }
        assertEquals(240f, profileHeroHeight(390f, 400f, 240f, 60f), .01f)
        assertEquals(0f, profileHeroHeight(390f, 400f, 0f, 60f), .01f)
    }

    @Test fun proportionalCropRetainsAtLeast85PercentAndUnknownOrVideoUsesFit() {
        val viewport = IntSize(340, 600)
        val atThreshold = heroMediaGeometry(viewport, IntSize(400, 600))
        assertTrue(atThreshold.cropped)
        assertEquals(Rect(0f, 0f, 340f, 600f), atThreshold.imageBounds)
        val outside = heroMediaGeometry(viewport, IntSize(401, 600))
        assertFalse(outside.cropped)
        val landscape = heroMediaGeometry(viewport, IntSize(1200, 300))
        assertFalse(landscape.cropped)
        assertEquals(340f, landscape.imageBounds!!.width, .01f)
        assertEquals(85f, landscape.imageBounds!!.height, .01f)
        val tall = heroMediaGeometry(viewport, IntSize(100, 1200))
        assertFalse(tall.cropped)
        assertEquals(50f, tall.imageBounds!!.width, .01f)
        val video = heroMediaGeometry(viewport, IntSize(400, 600), allowCrop = false)
        assertFalse(video.cropped)
        assertEquals(null, heroMediaGeometry(viewport, IntSize.Zero).imageBounds)
        assertFalse(heroMediaGeometry(IntSize.Zero, IntSize(340, 600)).cropped)
    }

    @Test fun photoGeometryExistsOnlyForCurrentCompletedDecodeAndRecomputesForResize() {
        val portrait = IntSize(320, 360)
        val wide = IntSize(640, 360)
        val photo = IntSize(300, 360)
        assertNull(settledHeroPhotoGeometry(portrait, null, true))
        assertTrue(requireNotNull(settledHeroPhotoGeometry(portrait, photo, true)).cropped)
        assertNull(settledHeroPhotoGeometry(portrait, null, true))
        assertFalse(requireNotNull(settledHeroPhotoGeometry(portrait, IntSize(160, 360), true)).cropped)
        assertNull(settledHeroPhotoGeometry(wide, null, true))
        assertFalse(requireNotNull(settledHeroPhotoGeometry(wide, photo, true)).cropped)
        assertFalse(requireNotNull(settledHeroPhotoGeometry(portrait, IntSize.Zero, true)).cropped)
        assertNull(settledHeroPhotoGeometry(IntSize.Zero, photo, true))
        assertFalse(requireNotNull(settledHeroPhotoGeometry(portrait, photo, false)).cropped)
    }

    @Test fun favoriteTargetFitsActualImageOrAdjacentRestAreaWithConstantPadding() {
        val viewport = IntSize(340, 600)
        val fill = heroMediaGeometry(viewport, IntSize(340, 600))
        assertEquals(IntOffset(286, 6), fill.favoriteOrigin(48, 6))
        val wide = heroMediaGeometry(viewport, IntSize(1200, 300))
        val wideBounds = wide.imageBounds!!
        val wideOrigin = wide.favoriteOrigin(48, 6)
        assertTrue(wideOrigin.y >= wideBounds.top + 6 && wideOrigin.y + 48 <= wideBounds.bottom - 6)
        val extreme = heroMediaGeometry(viewport, IntSize(100, 1200))
        val extremeBounds = extreme.imageBounds!!
        val extremeOrigin = extreme.favoriteOrigin(48, 6)
        assertTrue(extremeOrigin.x >= extremeBounds.right || extremeOrigin.x + 48 <= extremeBounds.left)
        assertEquals(IntOffset(286, 6), heroMediaGeometry(viewport, IntSize.Zero).favoriteOrigin(48, 6))
        assertEquals(IntOffset.Zero, heroMediaGeometry(IntSize(40, 40), IntSize.Zero).favoriteOrigin(48, 6))
    }

    @Test fun noticesAndRailCannotConsumeReservedBody() {
        for (height in listOf(112f, 140f, 200f, 239f, 240f, 320f, 480f, 760f)) {
            for (font in listOf(1f, 1.3f, 2f)) {
                val plan = profileShellBudget(360f, height, 80f, font, true, true)
                assertEquals(height, plan.header + plan.notice + plan.rail + plan.body, .01f)
                assertTrue(plan.body >= minOf(96f, height - plan.header))
                assertTrue(plan.notice == 0f || plan.notice == 48f)
                assertTrue(plan.rail in setOf(0f, ShellMetrics.touch.value, ShellMetrics.decisionRail.value))
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
                            if (rail) 48f * font + ShellMetrics.inset.value else null)
                        val context = "width=$width, height=$height, font=$font, system=$system, ime=$ime, rail=$rail"
                        assertEquals(context, usable, budget.header + budget.notice + budget.rail + budget.body, .01f)
                        assertTrue(context, budget.body >= minOf(96f, usable - budget.header) - .01f)
                        assertTrue(context, hero >= 0f && hero <= budget.body + .01f)
                        if (rail && budget.body - (48f * font + ShellMetrics.inset.value) >= 250f)
                            assertEquals(context, budget.body, hero + 48f * font + ShellMetrics.inset.value, .01f)
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
