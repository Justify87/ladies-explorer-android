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

/** Cache hits never queue behind network I/O. Window pins and playback leases have separate lifetimes. */
internal class GalleryVideoFiles(private val core: CoreStore) {
    private data class Entry(val video: LocalGalleryVideo, val bytes: Long, var users: Int = 0,
        val pins: MutableSet<Any> = mutableSetOf())
    private val gate = Mutex()
    private val downloadGate = Mutex()
    private val files = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val budget = 256L * 1024 * 1024
    private val warmBudget = budget - 48L * 1024 * 1024

    private fun discard(url: String, entry: Entry): Boolean {
        if (entry.video.file.exists() && !entry.video.file.delete()) return false
        files.remove(url)
        return true
    }

    private fun makeRoom(bytes: Long): Boolean {
        for ((url, entry) in files.toList()) {
            if (files.values.sumOf { it.bytes } + bytes <= budget) break
            if (entry.users == 0 && entry.pins.isEmpty()) discard(url, entry)
        }
        return files.values.sumOf { it.bytes } + bytes <= budget
    }

    private fun pin(entry: Entry, owner: Any?) {
        if (owner == null || owner in entry.pins) return
        val pinned = files.values.filter { it.pins.isNotEmpty() }.sumOf { it.bytes }
        check(entry.pins.isNotEmpty() || pinned + entry.bytes <= warmBudget) { "Video-Prefetch-Budget erreicht" }
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
        val archived = withContext(Dispatchers.IO) {
            item.localPath?.let { runCatching { localVideo(File(it)) }.getOrNull() }
        }
        if (archived != null) return use(archived)
        check(item.url.isNotBlank()) { "No video source" }
        var lease: Entry? = null
        suspend fun reuse() = withContext(Dispatchers.IO) {
            gate.withLock {
                val entry = files[item.url] ?: return@withLock
                if (!entry.video.file.isFile ||
                    (refresh && entry.users == 0 && entry.pins.isEmpty())) {
                    check(discard(item.url, entry)) { "Video-Cache-Datei nicht entfernbar" }
                    return@withLock
                }
                pin(entry, pinOwner)
                entry.users++
                lease = entry
            }
        }
        try {
            reuse()
            if (lease == null) downloadGate.withLock {
                reuse()
                if (lease == null) {
                    val download = core.loadDisplayVideo(item.url)
                    withContext(Dispatchers.IO) {
                        gate.withLock {
                            val bytes = download.bytes.size.toLong()
                            if (pinOwner != null) check(
                                files.values.filter { it.pins.isNotEmpty() }.sumOf { it.bytes } + bytes <= warmBudget
                            ) { "Video-Prefetch-Budget erreicht" }
                            check(makeRoom(bytes)) { "Video-Cache-Budget erreicht" }
                            val destination = core.createDisplayVideoFile()
                            try {
                                destination.writeBytes(download.bytes)
                                val video = checkNotNull(localVideo(destination)) { "Unsupported local video" }
                                val entry = Entry(video, destination.length(), users = 1)
                                pin(entry, pinOwner)
                                files[item.url] = entry
                                lease = entry
                            } catch (failure: Exception) {
                                destination.delete()
                                throw failure
                            }
                        }
                    }
                }
            }
            return use(checkNotNull(lease).video)
        } finally {
            if (lease != null) withContext(NonCancellable + Dispatchers.IO) {
                gate.withLock { checkNotNull(lease).users-- }
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
) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = mediaVisible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val playing = visible && active
    val playbackAllowed by rememberUpdatedState(active)
    val mediaKey = item.presentationKey()
    var retry by remember(mediaKey) { mutableIntStateOf(0) }
    var paused by remember(mediaKey) { mutableStateOf(false) }
    var file by remember(mediaKey, retry, visible) { mutableStateOf<LocalGalleryVideo?>(null) }
    var failure by remember(mediaKey, retry, visible) { mutableStateOf(false) }
    var ready by remember(mediaKey, retry, playing) { mutableStateOf(false) }
    var videoSize by remember(item, retry) { mutableStateOf(IntSize.Zero) }
    SideEffect { onVideoSize(if (failure) IntSize.Zero else videoSize) }
    LaunchedEffect(mediaKey, retry, visible) {
        if (!visible) return@LaunchedEffect
        snapshotFlow { playbackAllowed }.first { it }
        // Transient action locks must not trigger another download.
        try {
            val app = context.applicationContext as LadiesApplication
            app.galleryVideoFiles.withVideo(item, refresh = retry > 0) { prepared ->
                file = prepared
                awaitCancellation()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failure = true
        }
    }
    val video = file
    if (playing && video != null && !failure) {
        key(video, retry) {
            LocalMpvPlayer(video, paused, modifier, onLoading = { ready = false },
                onReady = { ready = true }, onFailure = { failure = true }, onSize = { videoSize = it })
        }
    } else Box(modifier)
    onTargets()
    Box(controlsModifier.testTag("gallery-video-control"), contentAlignment = Alignment.Center) {
        when {
            !playing -> IconButton(onClick = {}, enabled = false) { Icon(Icons.Default.PlayArrow, "Video abspielen") }
            failure -> IconButton(onClick = { if (canInteract()) { paused = false; retry++ } }) {
                Icon(Icons.Default.PlayArrow, "Video erneut laden")
            }
            !ready -> CircularProgressIndicator(Modifier.size(28.dp))
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
    video: LocalGalleryVideo, paused: Boolean, modifier: Modifier,
    onLoading: () -> Unit, onReady: () -> Unit, onFailure: () -> Unit,
    onSize: (IntSize) -> Unit,
) {
    val context = LocalContext.current
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
            MpvSurfaceOwner(context.applicationContext, video, { latestReady() }, { latestFailure() },
                onSurfaceLost = { surfaceGeneration++ }, onSize = { latestSize(it) })
        } catch (_: Exception) {
            latestFailure()
            null
        } catch (_: LinkageError) {
            latestFailure()
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
    private val onFailure: () -> Unit,
    private val onSurfaceLost: () -> Unit,
    private val onSize: (IntSize) -> Unit,
) : TextureView.SurfaceTextureListener, MPVLib.EventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val player = checkNotNull(MPVLib.create(context)) { "libmpv unavailable" }
    private var closed = false
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
                check(player.setOptionString(name, value) >= 0) { "libmpv option rejected: $name" }
            }
            player.addObserver(this)
            player.init()
        } catch (failure: Throwable) {
            runCatching { player.removeObserver(this) }
            runCatching { player.destroy() }
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
        } catch (_: Exception) {
            onFailure()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (!closed && surface != null) player.setPropertyString("android-surface-size", "${width}x$height")
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
        if (!closed && surface != null) player.setPropertyBoolean("pause", value)
    }

    fun close() {
        if (closed) return
        closed = true
        player.removeObserver(this)
        main.removeCallbacksAndMessages(null)
        try {
            player.destroy()
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
        }.getOrDefault(IntSize.Zero)
        if (dimensions.width > 0 && dimensions.height > 0) onSize(dimensions)
    }

    override fun event(eventId: Int) {
        main.post {
            if (closed || surface == null) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> { publishVideoSize(); onReady() }
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> publishVideoSize()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> onFailure()
            }
        }
    }
    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: String) = Unit
}
