package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*

internal fun fixtureProfile(snapshot: Long = 11L) = ProfileResponse(
    listingId = 4L, snapshotId = snapshot, newSnapshot = false, advertId = "fixture",
    name = "Fixture", city = "Fixture city", description = "", postalCode = null,
    hiddenLocation = null, sourceProfileId = null, canonicalId = null,
    phoneDisplay = null, publishedAt = null, traitsJson = "{}", servicesJson = "{}",
    openingTimesJson = "{}", media = emptyList(),
)

internal fun fixtureIdentity(owner: Long) = ProfileIdentity(
    personId = owner, status = "ACTIVE", profileUrl = null, personNew = false,
    listingNew = false, hasVideo = false, age = null,
)

internal fun fixturePerson(owner: Long) = PersonSummary(owner, "Fixture", Preference.NONE, false, 1u, "")
internal fun fixtureCard(listing: Long, owner: Long = listing) = DiscoveryCard(
    listingId = listing, personId = owner, name = "Fixture", city = "Fixture city",
    thumbnailUrl = null, localPath = null, description = null, preference = Preference.NONE,
    favorite = false, hasVideo = false, hasAiContent = false, isPremium = false, age = null,
)

internal fun fixtureSwipe(listing: Long = 4L, owner: Long = 1L, snapshot: Long = 11L) = ProfileReadModel(
    NativeProfileProjection(
        fixtureProfile(snapshot).copy(listingId = listing, phoneDisplay = "+49000000000"),
        fixtureIdentity(owner), fixturePerson(owner),
        listOf(ProfileMediaItem("IMAGE", "https://example.invalid/fixture.jpg", null, 0u)),
        emptyList(), emptyList(),
    ), true,
)
