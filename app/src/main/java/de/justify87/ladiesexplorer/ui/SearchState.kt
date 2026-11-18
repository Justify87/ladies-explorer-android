package de.justify87.ladiesexplorer.ui

import de.justify87.ladiesexplorer.core.LocalVisibility

internal fun defaultLocalVisibility() = LocalVisibility(
    videoOnly = false, photosOnly = false, hideDislikes = true,
    hideLikes = false, imageMode = "ALL",
)

enum class AvailabilityMode(val label: String, val value: String?) {
    All("Alle", null),
    Now("Jetzt", "jetzt geöffnet"),
    Today("Heute", "heute geöffnet"),
}

private val quickTimes = setOf("jetzt geöffnet", "jetzt-offen", "heute geöffnet", "heute-offen")

internal fun availabilityMode(filters: Map<String, Set<String>>): AvailabilityMode {
    val times = filters["zeiten"].orEmpty()
    return when {
        "jetzt geöffnet" in times || "jetzt-offen" in times -> AvailabilityMode.Now
        "heute geöffnet" in times || "heute-offen" in times -> AvailabilityMode.Today
        else -> AvailabilityMode.All
    }
}

internal fun withAvailability(filters: Map<String, Set<String>>, mode: AvailabilityMode): Map<String, Set<String>> {
    val times = filters["zeiten"].orEmpty().filterNot { it in quickTimes }.toMutableSet()
    mode.value?.let(times::add)
    return if (times.isEmpty()) filters - "zeiten" else filters + ("zeiten" to times.toSet())
}
