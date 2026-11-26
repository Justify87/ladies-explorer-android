package de.justify87.ladiesexplorer.ui

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import de.justify87.ladiesexplorer.core.CoreStore
import de.justify87.ladiesexplorer.core.DiagnosticLevel
import de.justify87.ladiesexplorer.core.mediaDiagnosticKey
import de.justify87.ladiesexplorer.core.mediaDiagnosticReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Recognize only MP4/EBML headers and select the corresponding demuxer. */
internal fun videoDemuxer(header: ByteArray): String? = when {
    header.size >= 8 && header.copyOfRange(4, 8).contentEquals(byteArrayOf(102, 116, 121, 112)) -> "mov"
    header.size >= 4 && header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())) -> "matroska"
    else -> null
}

private fun localVideo(file: File): LocalGalleryVideo? {
    if (!file.isFile || file.length() <= 0 || file.length() > 48L * 1024L * 1024L) return null
    val demuxer = file.inputStream().use { input ->
        val buffer = ByteArray(16)
        videoDemuxer(buffer.copyOf(input.read(buffer).coerceAtLeast(0)))
    } ?: return null
    return LocalGalleryVideo(file.canonicalFile, demuxer)
}

internal data class LocalGalleryVideo(val file: File, val demuxer: String)

internal class VideoPreparationDeferred : Exception("Video prefetch budget reached")

internal fun mpvDiagnosticMessage(prefix: String, text: String): String {
    val subsystem = when (prefix.substringBefore('/')) {
        "ffmpeg" -> "ffmpeg"
        "vd", "vo", "demux", "cplayer", "gpu", "lavf", "stream" -> prefix.substringBefore('/')
        else -> "other"
    }
    return "native subsystem=$subsystem reason=${mediaDiagnosticReason(text)}"
}

/** Cache hits never queue behind network I/O. Window pins and playback leases have separate lifetimes. */
internal class GalleryVideoFiles(
    private val downloadBytes: suspend (String) -> ByteArray,
    private val createFile: () -> File,
    private val budget: Long = 256L * 1024 * 1024,
    private val warmBudget: Long = budget - 48L * 1024 * 1024,
    private val diagnostic: (DiagnosticLevel, String, Throwable?) -> Unit = { _, _, _ -> },
) {
    constructor(core: CoreStore) : this({ url -> core.loadDisplayVideo(url).bytes }, core::createDisplayVideoFile,
        diagnostic = { level, message, error -> core.diagnostic("VIDEO_CACHE", level, message, error) })
    private fun report(level: DiagnosticLevel, url: String, event: String, error: Throwable? = null) =
        diagnostic(level, "$event media=${mediaDiagnosticKey(url)}", error)
    private fun pinnedBytes(): Long = files.values.filter { it.pins.isNotEmpty() }.sumOf { it.bytes }
    private data class ArchiveVersion(val path: String, val bytes: Long, val modified: Long)
    private data class Entry(val video: LocalGalleryVideo, val bytes: Long, var users: Int = 0,
        val rejectedArchive: ArchiveVersion? = null,
        val pins: MutableSet<Any> = mutableSetOf(), var replacing: Boolean = false,
        var idle: CompletableDeferred<Unit> = CompletableDeferred<Unit>().apply { if (users == 0) complete(Unit) })
    private val gate = Mutex()
    private val downloadGate = Mutex()
    private val files = LinkedHashMap<String, Entry>(16, 0.75f, true)

    private fun discard(url: String, entry: Entry): Boolean {
        if (entry.video.file.exists() && !entry.video.file.delete()) {
            report(DiagnosticLevel.WARN, url, "eviction_failed")
            return false
        }
        report(DiagnosticLevel.DEBUG, url, "evicted bytes=${entry.bytes}")
        files.remove(url)
        return true
    }

    private fun makeRoom(bytes: Long, replacing: Entry? = null): Boolean {
        fun required() = files.values.sumOf { it.bytes } - (replacing?.bytes ?: 0L) + bytes
        for ((url, entry) in files.toList()) {
            if (required() <= budget) break
            if (entry !== replacing && entry.users == 0 && entry.pins.isEmpty() && !entry.replacing) discard(url, entry)
        }
        return required() <= budget
    }

    private fun pin(entry: Entry, owner: Any?) {
        if (owner == null || owner in entry.pins) return
        if (entry.pins.isEmpty() && pinnedBytes() + entry.bytes > warmBudget) throw VideoPreparationDeferred()
        entry.pins.add(owner)
    }

    suspend fun retainPins(owner: Any, urls: Set<String>) = withContext(Dispatchers.IO) {
        gate.withLock {
            files.forEach { (url, entry) -> if (url !in urls) entry.pins.remove(owner) }
        }
    }

    suspend fun warm(item: ProfileMediaItem, owner: Any): Boolean =
        withVideo(item, pinOwner = owner) { true }

    suspend fun <T> withVideo(item: ProfileMediaItem, refresh: Boolean = false,
        pinOwner: Any? = null, use: suspend (LocalGalleryVideo) -> T): T {
        val archiveVersion = withContext(Dispatchers.IO) {
            item.localPath?.let { path -> File(path).let { ArchiveVersion(path, it.length(), it.lastModified()) } }
        }
        val archived = withContext(Dispatchers.IO) {
            val rejected = gate.withLock { files[item.url]?.rejectedArchive }
            item.localPath?.takeUnless {
                item.url.isNotBlank() && (refresh || (archiveVersion != null && rejected == archiveVersion))
            }?.let { path ->
                try {
                    localVideo(File(path)).also {
                        if (it == null) report(DiagnosticLevel.WARN, item.url.ifBlank { item.localPath.orEmpty() }, "archive_invalid")
                    }
                } catch (failure: Exception) {
                    report(DiagnosticLevel.WARN, item.url.ifBlank { item.localPath.orEmpty() }, "archive_read_failed", failure)
                    null
                }
            }
        }
        if (archived != null) {
            report(DiagnosticLevel.DEBUG, item.url.ifBlank { item.localPath.orEmpty() }, "hit source=archive")
            return use(archived)
        }
        check(item.url.isNotBlank()) { "No video source" }
        var lease: Entry? = null
        var replacement: Entry? = null
        suspend fun reuse(claimReplacement: Boolean) = withContext(Dispatchers.IO) {
            gate.withLock {
                val entry = files[item.url] ?: return@withLock
                if (entry.replacing && replacement !== entry) return@withLock
                val valid = try {
                    entry.video.file.length() == entry.bytes && localVideo(entry.video.file) == entry.video
                } catch (failure: Exception) {
                    report(DiagnosticLevel.WARN, item.url, "cache_read_failed", failure)
                    false
                }
                if (!valid || refresh) {
                    if (!valid) report(DiagnosticLevel.WARN, item.url, "cache_invalid")
                    // Keep the old entry and its pins until replacement under the same gate.
                    if (claimReplacement) {
                        replacement = entry
                        entry.replacing = true
                    }
                    return@withLock
                }
                report(DiagnosticLevel.DEBUG, item.url, "hit source=cache bytes=${entry.bytes}")
                pin(entry, pinOwner)
                if (entry.users++ == 0) entry.idle = CompletableDeferred()
                lease = entry
            }
        }
        try {
            reuse(false)
            if (lease == null) downloadGate.withLock {
                try {
                    reuse(true)
                    if (lease == null) {
                        val idle = gate.withLock { replacement?.idle }
                        if (idle != null && !idle.isCompleted) {
                            report(DiagnosticLevel.DEBUG, item.url, "replacement_waiting_for_lease")
                            check(withTimeoutOrNull(2_000) { idle.await(); true } == true) {
                                "Video cache replacement blocked by active playback"
                            }
                        }
                        if (pinOwner != null) gate.withLock {
                            if (replacement == null && pinnedBytes() >= warmBudget) throw VideoPreparationDeferred()
                        }
                        report(DiagnosticLevel.DEBUG, item.url, "miss")
                        val download = downloadBytes(item.url)
                        withContext(Dispatchers.IO) {
                            gate.withLock {
                                val bytes = download.size.toLong()
                                val old = replacement
                                check(old == null || (files[item.url] === old && old.users == 0)) {
                                    "Video cache replacement superseded or leased"
                                }
                                val inherited = old?.pins?.toMutableSet() ?: mutableSetOf()
                                if (pinOwner != null) inherited.add(pinOwner)
                                val otherPinned = pinnedBytes() - (old?.takeIf { it.pins.isNotEmpty() }?.bytes ?: 0L)
                                if (inherited.isNotEmpty() && otherPinned + bytes > warmBudget) {
                                    if (pinOwner != null) throw VideoPreparationDeferred()
                                    // Foreground playback may use the reserved space; do not exceed the pin budget.
                                    inherited.clear()
                                    report(DiagnosticLevel.DEBUG, item.url, "replacement_unpinned reason=budget")
                                }
                                check(videoDemuxer(download) != null && bytes in 1L..(48L * 1024 * 1024)) {
                                    "Unsupported local video"
                                }
                                if (!makeRoom(bytes, old)) {
                                    if (pinOwner != null) throw VideoPreparationDeferred()
                                    error("Video cache budget reached")
                                }
                                // Retain the old metadata/pins if writing the replacement fails.
                                if (old != null && old.video.file.exists()) check(old.video.file.delete()) {
                                    "Video cache replacement failed"
                                }
                                val destination = createFile()
                                try {
                                    destination.writeBytes(download)
                                    val video = checkNotNull(localVideo(destination)) { "Unsupported local video" }
                                    val entry = Entry(video, destination.length(), users = 1,
                                        rejectedArchive = if (refresh) archiveVersion ?: old?.rejectedArchive else old?.rejectedArchive)
                                    entry.pins.addAll(inherited)
                                    files[item.url] = entry
                                    lease = entry
                                    report(DiagnosticLevel.DEBUG, item.url, "stored bytes=${entry.bytes}")
                                } catch (failure: Exception) {
                                    if (!destination.delete() && destination.exists()) report(DiagnosticLevel.WARN, item.url, "cleanup_failed")
                                    throw failure
                                }
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        gate.withLock { replacement?.replacing = false }
                    }
                }
            }
            return use(checkNotNull(lease).video)
        } catch (cancel: CancellationException) {
            report(DiagnosticLevel.DEBUG, item.url, "cancelled")
            throw cancel
        } catch (deferred: VideoPreparationDeferred) {
            report(DiagnosticLevel.DEBUG, item.url, "deferred reason=budget")
            throw deferred
        } catch (failure: Exception) {
            report(DiagnosticLevel.WARN, item.url, "failed", failure)
            throw failure
        } finally {
            if (lease != null) withContext(NonCancellable + Dispatchers.IO) {
                gate.withLock {
                    if (--lease.users == 0) lease.idle.complete(Unit)
                }
            }
        }
    }
}

/** The visible medium owns the player and a lease on its local file. */
@Composable
internal fun GalleryVideo(
    item: ProfileMediaItem,
    active: Boolean,
    mediaVisible: Boolean,
    canInteract: () -> Boolean,
    modifier: Modifier,
    controlsModifier: Modifier,
    onTargets: @Composable () -> Unit,
    onVideoSize: (IntSize) -> Unit = {},
    prepared: PreparedMedium? = null,
    revision: Any? = null,
    onRecovered: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = mediaVisible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val playing = visible && active
    val playbackAllowed by rememberUpdatedState(active)
    val mediaKey = item.presentationKey()
    var retry by remember(mediaKey) { mutableIntStateOf(0) }
    var refreshPending by remember(mediaKey) { mutableStateOf(false) }
    var mediaFailed by remember(mediaKey) { mutableStateOf(false) }
    var attemptedSource by remember(mediaKey, retry) { mutableStateOf(item.localPath to revision) }
    val latestItem by rememberUpdatedState(item)
    val latestRevision by rememberUpdatedState(revision)
    val latestRecovered by rememberUpdatedState(onRecovered)
    var paused by remember(mediaKey) { mutableStateOf(false) }
    val unavailable = prepared == PreparedMedium.Unavailable
    var file by remember(mediaKey, retry, visible) { mutableStateOf<LocalGalleryVideo?>(null) }
    var failure by remember(mediaKey, retry, visible) { mutableStateOf(false) }
    var ready by remember(mediaKey, retry, playing) { mutableStateOf(false) }
    var videoSize by remember(mediaKey, retry) { mutableStateOf(IntSize.Zero) }
    SideEffect { onVideoSize(if (failure) IntSize.Zero else videoSize) }
    LaunchedEffect(failure, item.localPath, revision) {
        if (failure && attemptedSource != (item.localPath to revision)) {
            refreshPending = false
            retry++
        }
    }
    LaunchedEffect(ready, videoSize, unavailable, revision, failure) {
        if (!failure && ready && videoSize.width > 0 && videoSize.height > 0 && unavailable) latestRecovered()
    }
    LaunchedEffect(mediaKey, retry, visible) {
        if (!visible) return@LaunchedEffect
        snapshotFlow { playbackAllowed }.first { it }
        // Transient action locks must not trigger another download.
        try {
            val app = context.applicationContext as LadiesApplication
            val refresh = refreshPending
            attemptedSource = latestItem.localPath to latestRevision
            app.galleryVideoFiles.withVideo(latestItem, refresh = refresh) { prepared ->
                refreshPending = false
                mediaFailed = false
                file = prepared
                awaitCancellation()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            (context.applicationContext as LadiesApplication).coreStore.diagnostic("VIDEO", DiagnosticLevel.WARN,
                "playback_file_failed media=${mediaDiagnosticKey(item.url.ifBlank { item.localPath.orEmpty() })}", error)
            refreshPending = false
            mediaFailed = true
            failure = true
        }
    }
    val video = file
    if (playing && video != null && !failure) {
        key(video, retry) {
            LocalMpvPlayer(video, mediaDiagnosticKey(item.url.ifBlank { item.localPath.orEmpty() }), paused, modifier.graphicsLayer {
                alpha = if (ready && videoSize.width > 0 && videoSize.height > 0) 1f else 0f
            }, onLoading = { ready = false; videoSize = IntSize.Zero },
                onReady = { ready = true }, onFailure = { badMedia -> mediaFailed = badMedia; failure = true }, onSize = { videoSize = it })
        }
    } else Box(modifier)
    onTargets()
    Box(controlsModifier.testTag("gallery-video-control"), contentAlignment = Alignment.Center) {
        when {
            !playing -> IconButton(onClick = {}, enabled = false) { Icon(Icons.Default.PlayArrow, "Video abspielen") }
            failure -> IconButton(onClick = { if (canInteract()) { paused = false; refreshPending = mediaFailed; retry++ } }) {
                Icon(Icons.Default.PlayArrow, "Video erneut laden")
            }
            !ready || videoSize.width <= 0 || videoSize.height <= 0 -> CircularProgressIndicator(Modifier.size(28.dp))
            else -> FilledIconButton(onClick = { if (canInteract()) paused = !paused }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (paused) "Video abspielen" else "Video pausieren")
            }
        }
    }
}

/** TextureView supports Compose clipping; disposal revokes the Surface, player and callbacks. */
@Composable
private fun LocalMpvPlayer(
    video: LocalGalleryVideo, mediaKey: String, paused: Boolean, modifier: Modifier,
    onLoading: () -> Unit, onReady: () -> Unit, onFailure: (Boolean) -> Unit,
    onSize: (IntSize) -> Unit,
) {
    val context = LocalContext.current
    val core = (context.applicationContext as LadiesApplication).coreStore
    fun report(level: DiagnosticLevel, event: String, failure: Throwable? = null) =
        core.diagnostic("MPV", level, "$event media=$mediaKey", failure)
    val latestLoading by rememberUpdatedState(onLoading)
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onFailure)
    val latestSize by rememberUpdatedState(onSize)
    var owner by remember(video) { mutableStateOf<MpvSurfaceOwner?>(null) }
    var surfaceGeneration by remember(video) { mutableIntStateOf(0) }
    DisposableEffect(video, surfaceGeneration) {
        // Wait for the new instance's FILE_LOADED before exposing controls.
        latestLoading()
        val instance = try {
            MpvSurfaceOwner(context.applicationContext, video, { latestReady() }, { latestFailure(it) },
                onSurfaceLost = { surfaceGeneration++ }, onSize = { latestSize(it) },
                diagnostic = { level, event, failure -> report(level, event, failure) },
                debugEnabled = core::isDebugLoggingEnabled)
        } catch (failure: Exception) {
            report(DiagnosticLevel.ERROR, "init_failed", failure)
            latestFailure(false)
            null
        } catch (failure: LinkageError) {
            report(DiagnosticLevel.ERROR, "native_link_failed", failure)
            latestFailure(false)
            null
        }
        owner = instance
        onDispose {
            instance?.close()
            owner = null
        }
    }
    val player = owner
    if (player != null) key(player) {
        AndroidView(factory = { ctx ->
            TextureView(ctx).apply {
                isClickable = false
                isFocusable = false
                surfaceTextureListener = player
            }
        }, modifier = modifier, update = { player.pause(paused) })
    }
}

private class MpvSurfaceOwner(
    context: android.content.Context,
    private val source: LocalGalleryVideo,
    private val onReady: () -> Unit,
    private val onFailure: (Boolean) -> Unit,
    private val onSurfaceLost: () -> Unit,
    private val onSize: (IntSize) -> Unit,
    private val diagnostic: (DiagnosticLevel, String, Throwable?) -> Unit,
    private val debugEnabled: () -> Boolean,
) : TextureView.SurfaceTextureListener, MPVLib.EventObserver, MPVLib.LogObserver {
    private val main = Handler(Looper.getMainLooper())
    private val player = checkNotNull(MPVLib.create(context)) { "libmpv unavailable" }
    @Volatile private var closed = false
    private var surface: Surface? = null
    private var desiredPause = false

    init {
        try {
            // Fail closed: disable config/scripts, external references, playlists and network access.
            listOf(
                "config" to "no", "load-scripts" to "no", "ytdl" to "no",
                "autoload-files" to "no", "access-references" to "no",
                "demuxer" to "lavf", "demuxer-lavf-format" to source.demuxer,
                "demuxer-lavf-o" to "protocol_whitelist=file",
                "aid" to "no", "sid" to "no", "mute" to "yes", "sub-auto" to "no",
                "audio-file-auto" to "no", "loop-file" to "inf",
                "hwdec" to "mediacodec-copy", "vo" to "gpu", "gpu-context" to "android",
                "idle" to "yes", "force-window" to "no", "terminal" to "no",
                "keepaspect" to "yes", "panscan" to "0",
            ).forEach { (name, value) ->
                val result = player.setOptionString(name, value)
                if (result < 0) diagnostic(DiagnosticLevel.ERROR, "option_rejected name=$name code=$result", null)
                check(result >= 0) { "libmpv option rejected: $name" }
            }
            player.addObserver(this)
            player.addLogObserver(this)
            player.init()
            diagnostic(DiagnosticLevel.DEBUG, "initialized", null)
        } catch (failure: Throwable) {
            closed = true
            runCatching { player.removeObserver(this) }
            runCatching { player.removeLogObserver(this) }
            runCatching { player.destroy() }.onFailure {
                diagnostic(DiagnosticLevel.ERROR, "init_cleanup_failed", it)
            }
            throw failure
        }
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        try {
            val target = Surface(texture)
            surface = target
            player.attachSurface(target)
            player.setPropertyString("force-window", "yes")
            player.setPropertyString("android-surface-size", "${width}x$height")
            player.setPropertyBoolean("pause", desiredPause)
            // Pass only a validated local path to libmpv; Rust owns source HTTP.
            player.command(arrayOf("loadfile", source.file.absolutePath, "replace"))
            diagnostic(DiagnosticLevel.DEBUG, "surface_load_requested width=$width height=$height", null)
        } catch (failure: Exception) {
            diagnostic(DiagnosticLevel.ERROR, "surface_load_failed", failure)
            onFailure(false)
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (!closed && surface != null) try {
            player.setPropertyString("android-surface-size", "${width}x$height")
        } catch (failure: Exception) {
            diagnostic(DiagnosticLevel.ERROR, "surface_resize_failed", failure)
            onFailure(false)
        }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        // Join native event/render work before releasing the Surface; never reuse the old wid.
        if (!closed) {
            close()
            onSurfaceLost()
        }
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun pause(value: Boolean) {
        desiredPause = value
        if (!closed && surface != null) try {
            player.setPropertyBoolean("pause", value)
        } catch (failure: Exception) {
            diagnostic(DiagnosticLevel.ERROR, "pause_failed", failure)
            onFailure(false)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        player.removeObserver(this)
        player.removeLogObserver(this)
        main.removeCallbacksAndMessages(null)
        try {
            player.destroy()
            diagnostic(DiagnosticLevel.DEBUG, "closed", null)
        } catch (failure: Exception) {
            diagnostic(DiagnosticLevel.ERROR, "destroy_failed", failure)
        } finally {
            surface?.release()
            surface = null
        }
    }

    private fun publishVideoSize() {
        // The display dimensions include pixel aspect correction. Use a stable ratio
        // rather than feeding rounded surface margins back into the next surface resize.
        val dimensions = runCatching {
            val dw = player.getPropertyInt("dwidth") ?: 0
            val dh = player.getPropertyInt("dheight") ?: 0
            val rotation = player.getPropertyInt("video-out-params/rotate") ?: 0
            if (dw <= 0 || dh <= 0) IntSize.Zero
            else if (rotation % 180 != 0) IntSize(dh, dw) else IntSize(dw, dh)
        }.onFailure { diagnostic(DiagnosticLevel.WARN, "video_size_failed", it) }.getOrDefault(IntSize.Zero)
        if (dimensions.width > 0 && dimensions.height > 0) {
            diagnostic(DiagnosticLevel.DEBUG, "video_size width=${dimensions.width} height=${dimensions.height}", null)
            onSize(dimensions)
        }
    }

    override fun event(eventId: Int) {
        main.post {
            if (closed) return@post
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_QUEUE_OVERFLOW) {
                diagnostic(DiagnosticLevel.WARN, "native_event_queue_overflow", null)
                if (surface != null) onFailure(false)
                return@post
            }
            if (surface == null) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    diagnostic(DiagnosticLevel.DEBUG, "file_loaded", null)
                    publishVideoSize(); onReady()
                }
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> publishVideoSize()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    diagnostic(DiagnosticLevel.WARN, "end_file_while_active", null)
                    onFailure(true)
                }
            }
        }
    }
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (closed || level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_NONE) return
        val severity = when {
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR -> DiagnosticLevel.ERROR
            level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN -> DiagnosticLevel.WARN
            debugEnabled() -> DiagnosticLevel.DEBUG
            else -> return
        }
        diagnostic(severity, mpvDiagnosticMessage(prefix, text), null)
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: String) = Unit
}
