package androidx.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.activity.ComponentActivity

val ComponentActivity.lifecycleScope: CoroutineScope
    get() = CoroutineScope(Dispatchers.Main + SupervisorJob())
