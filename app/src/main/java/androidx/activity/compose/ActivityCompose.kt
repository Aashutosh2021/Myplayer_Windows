package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import javax.swing.JFileChooser

fun ComponentActivity.setContent(content: @Composable () -> Unit) {}

fun <I, O> ComponentActivity.registerForActivityResult(
    contract: ActivityResultContract<I, O>,
    callback: (O) -> Unit
): ActivityResultLauncher<I> {
    return object : ActivityResultLauncher<I>() {
        override fun launch(input: I) {}
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit
): ActivityResultLauncher<I> {
    return remember {
        object : ActivityResultLauncher<I>() {
            override fun launch(input: I) {
                if (contract is androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree) {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Select Music Folder"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val file = chooser.selectedFile
                        @Suppress("UNCHECKED_CAST")
                        onResult(android.net.Uri.fromFile(file) as O)
                    }
                } else if (contract is androidx.activity.result.contract.ActivityResultContracts.OpenDocument) {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.FILES_ONLY
                        dialogTitle = "Select File"
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val file = chooser.selectedFile
                        @Suppress("UNCHECKED_CAST")
                        onResult(android.net.Uri.fromFile(file) as O)
                    }
                }
            }
        }
    }
}
