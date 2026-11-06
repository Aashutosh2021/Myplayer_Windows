package androidx.activity.compose

import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

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
                when (contract) {
                    is androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree -> {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Music Folder"
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            @Suppress("UNCHECKED_CAST")
                            onResult(android.net.Uri.fromFile(file) as O)
                        }
                    }
                    is androidx.activity.result.contract.ActivityResultContracts.OpenDocument -> {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            dialogTitle = "Select Backup or File to Open"
                            fileFilter = FileNameExtensionFilter("JSON Backup Files (*.json)", "json")
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val file = chooser.selectedFile
                            @Suppress("UNCHECKED_CAST")
                            onResult(android.net.Uri.fromFile(file) as O)
                        }
                    }
                    is androidx.activity.result.contract.ActivityResultContracts.CreateDocument -> {
                        val suggestedName = (input as? String)?.ifBlank { "myplayer_backup.json" } ?: "myplayer_backup.json"
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.FILES_ONLY
                            dialogTitle = "Save Backup File"
                            selectedFile = File(System.getProperty("user.home"), suggestedName)
                            fileFilter = FileNameExtensionFilter("JSON Backup Files (*.json)", "json")
                        }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            var file = chooser.selectedFile
                            if (!file.name.contains(".") && contract.mimeType.contains("json", ignoreCase = true)) {
                                file = File(file.parentFile, "${file.name}.json")
                            }
                            @Suppress("UNCHECKED_CAST")
                            onResult(android.net.Uri.fromFile(file) as O)
                        }
                    }
                }
            }
        }
    }
}
