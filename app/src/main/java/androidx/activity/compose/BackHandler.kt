package androidx.activity.compose

import androidx.compose.runtime.Composable

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    // Desktop BackHandler hook (e.g., ESC key or Back button handled at window level)
}
