package androidx.activity.result.contract

abstract class ActivityResultContract<I, O>

class ActivityResultContracts {
    class RequestPermission : ActivityResultContract<String, Boolean>()
    class OpenDocumentTree : ActivityResultContract<android.net.Uri?, android.net.Uri?>()
    class CreateDocument(val mimeType: String = "") : ActivityResultContract<String, android.net.Uri?>()
    class OpenDocument : ActivityResultContract<Array<String>, android.net.Uri?>()
}
