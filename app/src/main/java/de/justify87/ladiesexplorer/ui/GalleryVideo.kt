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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.justify87.ladiesexplorer.LadiesApplication
import de.justify87.ladiesexplorer.core.ProfileMediaItem
import dev.jdtech.mpv.MPVLib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.io.File

/** Only ordinary local MP4/WebM containers are handed to the player.
 * Never pass source URLs, playlist paths, or arbitrary schemes to libmpv. */
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

private data class LocalGalleryVideo(val file: File, val demuxer: String)

/** One selected, visible medium owns one cancellable display file and one player.
 * The file is not a durable archive and its lifetime is bounded by this effect.
 * Archive locations are never deleted. */
@Composable
internal fun GalleryVideo(
    item: ProfileMediaItem,
    active: Boolean,
    mediaVisible: Boolean,
    canInteract: () -> Boolean,
    modifier: Modifier,
    controlsModifier: Modifier,
    onTargets: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visible = mediaVisible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val playing = visible && active
    var retry by remember(item) { mutableIntStateOf(0) }
    var paused by remember(item) { mutableStateOf(false) }
    var file by remember(item, retry, visible) { mutableStateOf<LocalGalleryVideo?>(null) }
    var failure by remember(item, retry, visible) { mutableStateOf(false) }
    var ready by remember(item, retry, visible) { mutableStateOf(false) }
    LaunchedEffect(item, retry, visible) {
        if (!visible) return@LaunchedEffect
        // A transient action lock must not redownload a selected medium.
        // The file remains owned only while the media is actually visible.
        var temporary: File? = null
        try {
            val archived = withContext(Dispatchers.IO) {
                item.localPath?.let { runCatching { localVideo(File(it)) }.getOrNull() }
            }
            if (archived != null) {
                file = archived
            } else {
                val app = context.applicationContext as LadiesApplication
                val download = app.coreStore.loadDisplayVideo(item.url)
                // Allocate the path before the cancellable IO section so finally
                // can always remove it, even if delivery is cancelled.
                temporary = File.createTempFile("gallery-video-", ".media", context.cacheDir)
                val destination = checkNotNull(temporary)
                file = withContext(Dispatchers.IO) {
                    destination.writeBytes(download.bytes)
                    checkNotNull(localVideo(destination)) { "Unsupported local video" }
                }
            }
            awaitCancellation()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failure = true
        } finally {
            temporary?.delete()
        }
    }
    val video = file
    if (playing && video != null && !failure) {
        key(video, retry) {
            LocalMpvPlayer(video, paused, modifier, onReady = { ready = true }, onFailure = { failure = true })
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

/** TextureView composites inside Compose clipping/transforms. Its Surface and
 * the native instance have the same owner, and are synchronously revoked on
 * disposal. Callback delivery is fenced after destroy. */
@Composable
private fun LocalMpvPlayer(
    video: LocalGalleryVideo, paused: Boolean, modifier: Modifier,
    onReady: () -> Unit, onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val latestReady by rememberUpdatedState(onReady)
    val latestFailure by rememberUpdatedState(onFailure)
    var owner by remember(video) { mutableStateOf<MpvSurfaceOwner?>(null) }
    DisposableEffect(video) {
        val instance = try {
            MpvSurfaceOwner(context.applicationContext, video, { latestReady() }, { latestFailure() })
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
    if (player != null) {
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
) : TextureView.SurfaceTextureListener, MPVLib.EventObserver {
    private val main = Handler(Looper.getMainLooper())
    private val player = checkNotNull(MPVLib.create(context)) { "libmpv unavailable" }
    private var closed = false
    private var surface: Surface? = null
    private var desiredPause = false

    init {
        try {
            // Fail closed: no player config/scripts, playlists, external media
            // references or network protocols. Native transport remains Rust.
            listOf(
                "config" to "no", "load-scripts" to "no", "ytdl" to "no",
                "autoload-files" to "no", "access-references" to "no",
                "demuxer" to "lavf", "demuxer-lavf-format" to source.demuxer,
                "demuxer-lavf-o" to "protocol_whitelist=file",
                "aid" to "no", "mute" to "yes", "sub-auto" to "no",
                "audio-file-auto" to "no", "loop-file" to "inf",
                "hwdec" to "mediacodec-copy", "vo" to "gpu", "gpu-context" to "android",
                "idle" to "yes", "force-window" to "no", "terminal" to "no",
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
            player.setPropertyString("android-surface-size", "${width}x$height")
            player.setPropertyBoolean("pause", desiredPause)
            // Only a private/archived local file path, never item.url.
            player.command(arrayOf("loadfile", source.file.absolutePath, "replace"))
        } catch (_: Exception) {
            onFailure()
        }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        if (!closed && surface != null) player.setPropertyString("android-surface-size", "${width}x$height")
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        detach()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

    fun pause(value: Boolean) {
        desiredPause = value
        if (!closed && surface != null) player.setPropertyBoolean("pause", value)
    }

    private fun detach() {
        if (surface != null) {
            if (!closed) {
                runCatching { player.command(arrayOf("stop")) }
                runCatching { player.detachSurface() }
            }
            surface?.release()
            surface = null
        }
    }

    fun close() {
        if (closed) return
        detach()
        closed = true
        player.removeObserver(this)
        runCatching { player.destroy() }
    }

    override fun event(eventId: Int) {
        main.post {
            if (closed || surface == null) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> onReady()
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
