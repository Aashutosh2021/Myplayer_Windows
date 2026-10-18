package androidx.core.content

import android.content.Context
import java.util.concurrent.Executor
import javax.swing.SwingUtilities

object ContextCompat {
    @JvmStatic
    fun getMainExecutor(context: Context): Executor {
        return Executor { command -> SwingUtilities.invokeLater(command) }
    }

    @JvmStatic
    fun checkSelfPermission(context: Context, permission: String): Int = 0 // PERMISSION_GRANTED
}
