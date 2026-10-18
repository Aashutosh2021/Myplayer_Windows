package androidx.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContract

open class ComponentActivity : Context() {
    val lifecycleScope: kotlinx.coroutines.CoroutineScope get() = kotlinx.coroutines.GlobalScope

    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onStart() {}
    open fun onResume() {}
    open fun onPause() {}
    open fun onStop() {}
    open fun onDestroy() {}

    fun <I, O> registerForActivityResult(
        contract: ActivityResultContract<I, O>,
        callback: (O) -> Unit
    ): ActivityResultLauncher<I> {
        return ActivityResultLauncher { /* no-op on desktop */ }
    }
}

fun interface ActivityResultLauncher<I> {
    fun launch(input: I)
}

fun ComponentActivity.enableEdgeToEdge() {}
fun ComponentActivity.setContent(content: @androidx.compose.runtime.Composable () -> Unit) {}
