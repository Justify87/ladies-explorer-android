package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.*

/** Revision is an in-memory delivery token; SQLite remains authoritative. */
data class ProfileReadModel(
    val local: NativeProfileProjection,
    val fromArchive: Boolean,
    val warning: String? = null,
    val revision: Long = 0L,
) {
    val profile get() = local.profile
    val identity get() = local.identity
    val person get() = local.person
    val media get() = local.media
    val history get() = local.history
    val candidates get() = local.candidates
}
