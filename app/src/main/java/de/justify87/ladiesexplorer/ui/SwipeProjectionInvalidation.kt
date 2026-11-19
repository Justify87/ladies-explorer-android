package de.justify87.ladiesexplorer.ui

/** Existing swipe source completion and visibility policy; no additional projection or queue. */
internal class SwipeProjectionInvalidation(
    private val savedSearch: () -> Unit,
    private val discovery: () -> Unit,
    private val map: () -> Unit,
) {
    var pending = false
        private set
    private var dirty = false
    private var listVisible = false
    private var mapVisible = false

    fun sourceCompleted() {
        dirty = true
        savedSearch()
        if (listVisible) discovery()
        if (mapVisible) map()
    }

    fun setVisibility(listVisible: Boolean, mapVisible: Boolean) {
        val mapEntered = mapVisible && !this.mapVisible
        this.listVisible = listVisible
        this.mapVisible = mapVisible
        if (listVisible && dirty) discovery()
        if (mapEntered) map()
    }

    /** Busy refresh is queued; a real read consumes the pending and dirty flags. */
    fun beginDiscovery(busy: Boolean): Boolean {
        pending = busy
        if (busy) return false
        dirty = false
        return true
    }
}
