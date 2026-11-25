package de.justify87.ladiesexplorer.ui

internal enum class RootDestination(val title: String) {
    Discover("Entdecken"),
    Search("Suche"),
    Saved("Gespeichert"),
    Settings("Einstellungen"),
}

internal enum class DiscoverMode(val title: String) {
    Swipe("Swipe"),
    Grid("Raster"),
    Map("Karte"),
}

internal enum class SettingsPane(val title: String) {
    Main("Einstellungen"),
    Search("Suche"),
    Discovery("Entdecken"),
    Diagnostics("Diagnose und Fehlerbehebung"),
}

internal enum class BackAction { CloseProfile, CloseSavedResults, Navigate, System }

internal data class ShellRoute(
    val root: RootDestination = RootDestination.Discover,
    val discovery: DiscoverMode = DiscoverMode.Swipe,
    val swipeOrigin: DiscoverMode? = null,
    val settingsPane: SettingsPane = SettingsPane.Main,
) {
    val mapVisible get() = root == RootDestination.Discover && discovery == DiscoverMode.Map
    val stateKey get() = when (root) {
        RootDestination.Discover -> "discover/${discovery.name}"
        RootDestination.Settings -> "settings/${settingsPane.name}"
        else -> root.name
    }
    val canGoBack get() = root != RootDestination.Discover || discovery != DiscoverMode.Swipe || swipeOrigin != null

    fun select(destination: RootDestination) = copy(root = destination, swipeOrigin = null, settingsPane = SettingsPane.Main)
    fun select(mode: DiscoverMode) = copy(root = RootDestination.Discover, discovery = mode, swipeOrigin = null, settingsPane = SettingsPane.Main)

    fun openSettings(pane: SettingsPane) =
        if (root == RootDestination.Settings && settingsPane == SettingsPane.Main) copy(settingsPane = pane) else this

    fun openSwipe() = if (root == RootDestination.Discover && discovery != DiscoverMode.Swipe)
        copy(discovery = DiscoverMode.Swipe, swipeOrigin = discovery) else this

    fun back() = when {
        root == RootDestination.Settings && settingsPane != SettingsPane.Main -> copy(settingsPane = SettingsPane.Main)
        root != RootDestination.Discover -> copy(root = RootDestination.Discover, settingsPane = SettingsPane.Main)
        swipeOrigin != null -> copy(discovery = swipeOrigin, swipeOrigin = null)
        discovery != DiscoverMode.Swipe -> copy(discovery = DiscoverMode.Swipe)
        else -> this
    }

    fun backAction(profileOpen: Boolean, savedResultsOpen: Boolean) = when {
        profileOpen -> BackAction.CloseProfile
        savedResultsOpen -> BackAction.CloseSavedResults
        canGoBack -> BackAction.Navigate
        else -> BackAction.System
    }

    fun encode() = arrayListOf(root.name, discovery.name, swipeOrigin?.name.orEmpty(), settingsPane.name)

    companion object {
        fun decode(parts: List<String>): ShellRoute {
            val root = RootDestination.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: RootDestination.Discover
            val mode = DiscoverMode.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: DiscoverMode.Swipe
            val origin = DiscoverMode.entries.firstOrNull { it.name == parts.getOrNull(2) && it != DiscoverMode.Swipe }
                ?.takeIf { root == RootDestination.Discover && mode == DiscoverMode.Swipe }
            val settings = SettingsPane.entries.firstOrNull { it.name == parts.getOrNull(3) }
                ?.takeIf { root == RootDestination.Settings } ?: SettingsPane.Main
            return ShellRoute(root, mode, origin, settings)
        }
    }
}
