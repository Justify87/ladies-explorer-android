package de.justify87.ladiesexplorer.ui

internal enum class RootDestination(val title: String) {
    Discover("Entdecken"),
    Search("Suche"),
    Saved("Gespeichert"),
    Settings("Einstellungen"),
}

internal enum class DiscoverMode(val title: String) {
    Swipe("Swipe"),
    Grid("Grid"),
    Map("Karte"),
}

internal enum class BackAction { CloseProfile, CloseSavedResults, Navigate, System }

internal data class ShellRoute(
    val root: RootDestination = RootDestination.Discover,
    val discovery: DiscoverMode = DiscoverMode.Swipe,
) {
    val mapVisible get() = root == RootDestination.Discover && discovery == DiscoverMode.Map
    val stateKey get() = if (root == RootDestination.Discover) "discover/${discovery.name}" else root.name
    val canGoBack get() = root != RootDestination.Discover || discovery != DiscoverMode.Swipe

    fun select(destination: RootDestination) = copy(root = destination)
    fun select(mode: DiscoverMode) = copy(root = RootDestination.Discover, discovery = mode)
    fun back() = when {
        root != RootDestination.Discover -> copy(root = RootDestination.Discover)
        discovery != DiscoverMode.Swipe -> copy(discovery = DiscoverMode.Swipe)
        else -> this
    }

    fun backAction(profileOpen: Boolean, savedResultsOpen: Boolean) = when {
        profileOpen -> BackAction.CloseProfile
        savedResultsOpen -> BackAction.CloseSavedResults
        canGoBack -> BackAction.Navigate
        else -> BackAction.System
    }

    fun encode() = arrayListOf(root.name, discovery.name)

    companion object {
        fun decode(parts: List<String>): ShellRoute = ShellRoute(
            root = RootDestination.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: RootDestination.Discover,
            discovery = DiscoverMode.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: DiscoverMode.Swipe,
        )
    }
}
