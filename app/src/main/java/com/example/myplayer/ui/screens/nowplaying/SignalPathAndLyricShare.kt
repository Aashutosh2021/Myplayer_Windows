package com.example.myplayer.ui.screens.nowplaying

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myplayer.playback.AudioQualityInfo
import java.awt.Desktop
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 1. Desktop Audiophile Signal Path Inspector Dialog for MyPlayer Windows.
 */
@Composable
fun SignalPathInspectorDialog(
    qualityInfo: AudioQualityInfo,
    eqEnabled: Boolean = false,
    spatialEnabled: Boolean = false,
    autoEqModel: String? = null,
    outputDeviceName: String = "Windows JavaSound 48kHz PCM (WASAPI / DirectSound)",
    onDismiss: () -> Unit
) {
    val bitrateText = qualityInfo.bitrateKbps?.let { " ($it kbps)" }.orEmpty()
    val sampleRateText = qualityInfo.sampleRateHz?.let { "$it Hz" } ?: "48000 Hz"
    val bitDepthText = qualityInfo.bitDepth?.let { "$it-bit" } ?: "16-bit"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF14161F),
        titleContentColor = Color.White,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = Color(0xFF00E676))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Audio Signal Path", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        text = "Windows 64-Bit Dual-Deck DSP Pipeline",
                        color = Color(0xFF00E676),
                        fontSize = 12.sp
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalNodeRow(
                    step = "1. Source Stream",
                    title = "${qualityInfo.format.ifBlank { "AUTO" }}$bitrateText",
                    subtitle = if (qualityInfo.isLossless) "Verified Lossless Stream (${qualityInfo.sourceName})" else "${qualityInfo.sourceName} (Desktop Chunk Stream Proxy)"
                )
                SignalNodeRow(
                    step = "2. Audio Decoder",
                    title = "VLCJ / LibVLC SoXR Resampler",
                    subtitle = "$sampleRateText • $bitDepthText Stereo PCM"
                )
                SignalNodeRow(
                    step = "3. MyPlayer DSP Engine",
                    title = buildString {
                        val active = mutableListOf<String>()
                        if (eqEnabled) active.add("15-Band Rainbow EQ")
                        if (!autoEqModel.isNullOrBlank()) active.add("AutoEq ($autoEqModel)")
                        if (spatialEnabled) active.add("3D Spatial Audio")
                        if (active.isEmpty()) append("Direct Bypass (0 dB Bit-Transparent)") else append(active.joinToString(" + "))
                    },
                    subtitle = "DesktopMyPlayerDspPipeline + ReplayGain + Soft-Clip Limiter"
                )
                SignalNodeRow(
                    step = "4. Hardware Output",
                    title = outputDeviceName,
                    subtitle = "Low-Latency 150ms SourceDataLine Buffer + Dual-Deck Crossfade"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF00E676))
            }
        }
    )
}

@Composable
private fun SignalNodeRow(step: String, title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.dp)
    ) {
        Text(step, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

/**
 * 2. Desktop Shareable PNG Lyric Card Generator using Java2D (`BufferedImage` + `ImageIO`).
 * Saves to `~/Pictures/MyPlayer_Lyrics/` and opens the exported PNG in Windows Photos.
 */
object LyricCardExporter {
    fun shareLyricCardPng(
        context: Context,
        songTitle: String,
        artistName: String,
        selectedLines: List<String>
    ): File? {
        return runCatching {
            val width = 1080
            val height = 1350
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Gradient background
            g.paint = GradientPaint(0f, 0f, java.awt.Color(0x0F, 0x17, 0x2A), width.toFloat(), height.toFloat(), java.awt.Color(0x06, 0x4E, 0x3B))
            g.fillRect(0, 0, width, height)

            // Frosted card
            g.color = java.awt.Color(255, 255, 255, 34)
            g.fill(RoundRectangle2D.Float(72f, 120f, width - 144f, height - 240f, 56f, 56f))

            // Title & artist
            g.color = java.awt.Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 46)
            g.drawString(songTitle.take(32), 130, 230)

            g.color = java.awt.Color(255, 255, 255, 180)
            g.font = Font("SansSerif", Font.PLAIN, 32)
            g.drawString(artistName.take(40), 130, 285)

            // Lyrics
            g.color = java.awt.Color.WHITE
            g.font = Font("SansSerif", Font.BOLD, 50)
            val fm = g.fontMetrics
            var y = 440
            for (line in selectedLines.take(5)) {
                val words = line.split(" ")
                var currentLine = StringBuilder()
                for (w in words) {
                    val test = if (currentLine.isEmpty()) w else "$currentLine $w"
                    if (fm.stringWidth(test) > (width - 260)) {
                        g.drawString(currentLine.toString(), 130, y)
                        y += 72
                        currentLine = StringBuilder(w)
                    } else {
                        currentLine = StringBuilder(test)
                    }
                }
                if (currentLine.isNotEmpty()) {
                    g.drawString(currentLine.toString(), 130, y)
                    y += 90
                }
            }

            // Footer
            g.color = java.awt.Color(0x00, 0xE6, 0x76)
            g.font = Font("SansSerif", Font.BOLD, 30)
            g.drawString("♪ Shared via MyPlayer Desktop", 130, height - 180)
            g.dispose()

            val userHome = System.getProperty("user.home").orEmpty()
            val outDir = File(File(userHome, "Pictures"), "MyPlayer_Lyrics").takeIf {
                userHome.isNotBlank()
            } ?: File(context.cacheDir, "shared_lyrics")
            outDir.mkdirs()

            val safeName = songTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32).ifBlank { "lyric_card" }
            val outFile = File(outDir, "${safeName}_${System.currentTimeMillis()}.png")
            ImageIO.write(image, "png", outFile)

            runCatching {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(outFile)
            }
            outFile
        }.getOrNull()
    }
}
