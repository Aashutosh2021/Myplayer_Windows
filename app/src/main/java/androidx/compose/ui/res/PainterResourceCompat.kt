package androidx.compose.ui.res

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.InputStream
import javax.imageio.ImageIO

@Composable
fun painterResource(id: Int): Painter {
    return remember(id) {
        try {
            val resourcePath = when (id) {
                com.example.myplayer.R.drawable.inside_logo -> "/drawable/inside_logo.png"
                com.example.myplayer.R.drawable.app_icon_spark -> "/drawable/app_icon_spark.png"
                else -> "/drawable/inside_logo.png"
            }
            val stream: InputStream? = object {}.javaClass.getResourceAsStream(resourcePath)
            if (stream != null) {
                val bufferedImage = ImageIO.read(stream)
                if (bufferedImage != null) {
                    BitmapPainter(bufferedImage.toComposeImageBitmap())
                } else {
                    ColorPainter(Color(0xFF8B5CF6))
                }
            } else {
                ColorPainter(Color(0xFF8B5CF6))
            }
        } catch (_: Exception) {
            ColorPainter(Color(0xFF8B5CF6))
        }
    }
}
