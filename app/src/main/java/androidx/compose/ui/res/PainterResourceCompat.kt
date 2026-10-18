package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import java.io.InputStream
import javax.imageio.ImageIO

@Composable
fun painterResource(id: Int): Painter {
    val density = LocalDensity.current
    return remember(id, density) {
        try {
            val resourcePath = when (id) {
                com.example.myplayer.R.drawable.inside_logo -> "/drawable/inside_logo.svg"
                com.example.myplayer.R.drawable.app_icon_spark -> "/drawable/app_icon_spark.svg"
                else -> "/drawable/inside_logo.svg"
            }
            val stream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
                ?: object {}.javaClass.getResourceAsStream(resourcePath.replace(".svg", ".png"))
            if (stream != null) {
                if (resourcePath.endsWith(".svg")) {
                    loadSvgPainter(stream, density)
                } else {
                    val bufferedImage = ImageIO.read(stream)
                    if (bufferedImage != null) {
                        BitmapPainter(bufferedImage.toComposeImageBitmap())
                    } else {
                        ColorPainter(Color(0xFF8B5CF6))
                    }
                }
            } else {
                ColorPainter(Color(0xFF8B5CF6))
            }
        } catch (_: Exception) {
            ColorPainter(Color(0xFF8B5CF6))
        }
    }
}

