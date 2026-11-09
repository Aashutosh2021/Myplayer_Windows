package coil.compose

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import coil.request.ImageRequest
import com.example.myplayer.data.artwork.ArtworkRepository
import com.example.myplayer.data.artwork.model.ArtworkModel
import com.example.myplayer.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLDecoder

private val YT_THUMB_VIDEO_ID_REGEX = Regex("""(?:i\.ytimg\.com/vi(?:_webp)?/|img\.youtube\.com/vi/)([a-zA-Z0-9_-]{11})/""")

/**
 * Resolves offline local artwork files (in `~/.myplayer/files/artwork/<videoId>.jpg`,
 * companion `<song>.jpg`, or embedded ID3/MP4 cover art) and [ArtworkModel] requests
 * before delegating to [coil3.compose.AsyncImage].
 *
 * FIXED FOR WINDOWS DESKTOP:
 * Matches both `C:\...` and `C:/...` Windows drive paths and decodes `%20` URL-encoded file paths.
 */
private fun resolveImmediateDesktopModel(rawModel: Any?): Any? {
    val unwrapped = if (rawModel is ImageRequest) rawModel.data else rawModel
    if (unwrapped is File) return unwrapped
    if (unwrapped is ArtworkModel) {
        val local = unwrapped.localUri
        if (!local.isNullOrBlank()) {
            resolveLocalArtworkFile(local)?.let { return it }
        }
        return unwrapped
    }
    if (unwrapped is String) {
        if (unwrapped.isBlank()) return null
        resolveLocalArtworkFile(unwrapped)?.let { return it }
    }
    return unwrapped
}

private fun resolveLocalArtworkFile(uriOrUrl: String): File? {
    val clean = uriOrUrl.trim()
    val artworkDir = File(Context.DEFAULT.filesDir, "artwork")

    // 1. Direct local file or companion image next to audio file (supports both C:\ and C:/ on Windows)
    if (clean.startsWith("file:", ignoreCase = true) ||
        clean.startsWith("/") ||
        clean.matches(Regex("^[a-zA-Z]:[\\\\/].*"))
    ) {
        val stripped = clean
            .removePrefix("file:///")
            .removePrefix("file://")
            .removePrefix("file:/")
            .removePrefix("file:")
        val decoded = runCatching { URLDecoder.decode(stripped, "UTF-8") }.getOrDefault(stripped.replace("%20", " "))
        val filePath = if (decoded.matches(Regex("^/[a-zA-Z]:[\\\\/].*"))) decoded.substring(1) else decoded
        val f = File(filePath)
        val ext = f.extension.lowercase()
        if (f.exists() && ext in setOf("jpg", "jpeg", "png", "webp")) {
            return f
        }
        if (f.exists() && ext in setOf("m4a", "mp3", "flac", "wav", "ogg", "aac")) {
            val companionJpg = File(f.parentFile, "${f.nameWithoutExtension}.jpg")
            if (companionJpg.exists() && companionJpg.length() > 0) return companionJpg
            val companionPng = File(f.parentFile, "${f.nameWithoutExtension}.png")
            if (companionPng.exists() && companionPng.length() > 0) return companionPng
            val byNameInArtDir = File(artworkDir, "${f.nameWithoutExtension}.jpg")
            if (byNameInArtDir.exists() && byNameInArtDir.length() > 0) return byNameInArtDir

            val cachedExtracted = File(artworkDir.apply { mkdirs() }, "embedded_${f.nameWithoutExtension.hashCode()}.jpg")
            if (cachedExtracted.exists() && cachedExtracted.length() > 0) return cachedExtracted
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bytes = retriever.getEmbeddedPicture()
                if (bytes != null && bytes.isNotEmpty()) {
                    cachedExtracted.writeBytes(bytes)
                    return cachedExtracted
                }
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }
    }

    // 2. If it's a YouTube thumbnail URL, check if we have a saved local artwork file!
    val ytMatch = YT_THUMB_VIDEO_ID_REGEX.find(clean)
    if (ytMatch != null) {
        val videoId = ytMatch.groupValues[1]
        val localArt = File(artworkDir, "$videoId.jpg")
        if (localArt.exists() && localArt.length() > 512L) {
            return localArt
        }
    }

    return null
}

@Composable
fun AsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    error: Painter? = null,
    fallback: Painter? = null,
    onSuccess: (() -> Unit)? = null,
    onError: (() -> Unit)? = null
) {
    val immediateModel = remember(model) { resolveImmediateDesktopModel(model) }
    var resolvedModel by remember(immediateModel) {
        mutableStateOf(if (immediateModel is ArtworkModel) null else immediateModel)
    }

    if (immediateModel is ArtworkModel) {
        LaunchedEffect(immediateModel) {
            val fetched = withContext(Dispatchers.IO) {
                try {
                    val repo = AppContainer.resolveDependency(ArtworkRepository::class.java) as? ArtworkRepository
                    val res = repo?.getArtwork(
                        title = immediateModel.title,
                        artist = immediateModel.artist,
                        album = immediateModel.album,
                        localArtworkUri = immediateModel.localUri
                    )
                    val target = res?.url ?: immediateModel.localUri
                    if (!target.isNullOrBlank()) {
                        resolveLocalArtworkFile(target) ?: target
                    } else null
                } catch (_: Exception) {
                    immediateModel.localUri
                }
            }
            resolvedModel = fetched
        }
    }

    coil3.compose.AsyncImage(
        model = resolvedModel,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        error = error,
        fallback = fallback
    )
}