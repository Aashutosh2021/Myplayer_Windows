package androidx.documentfile.provider

import android.content.Context
import android.net.Uri
import java.io.File

open class DocumentFile(private val file: File) {
    val uri: Uri get() = Uri.fromFile(file)
    val name: String? get() = file.name
    val isDirectory: Boolean get() = file.isDirectory
    val isFile: Boolean get() = file.isFile
    fun length(): Long = file.length()
    fun exists(): Boolean = file.exists()
    fun canRead(): Boolean = file.canRead()
    fun canWrite(): Boolean = file.canWrite()
    fun delete(): Boolean = file.delete()
    fun renameTo(displayName: String): Boolean {
        val dest = File(file.parentFile ?: return false, displayName)
        return file.renameTo(dest)
    }

    fun listFiles(): Array<DocumentFile> {
        val children = file.listFiles() ?: return emptyArray()
        return children.map { DocumentFile(it) }.toTypedArray()
    }

    fun findFile(displayName: String): DocumentFile? {
        val target = File(file, displayName)
        return if (target.exists()) DocumentFile(target) else null
    }

    fun createFile(mimeType: String, displayName: String): DocumentFile? {
        val target = File(file, displayName)
        try {
            target.createNewFile()
            return DocumentFile(target)
        } catch (_: Exception) {
            return null
        }
    }

    companion object {
        @JvmStatic
        fun fromFile(file: File): DocumentFile = DocumentFile(file)

        @JvmStatic
        fun fromTreeUri(context: Context, treeUri: Uri): DocumentFile? {
            val path = treeUri.path ?: return null
            val f = File(path)
            return if (f.exists()) DocumentFile(f) else null
        }

        @JvmStatic
        fun fromSingleUri(context: Context, uri: Uri): DocumentFile? {
            val path = uri.path ?: return null
            val f = File(path)
            return if (f.exists()) DocumentFile(f) else null
        }
    }
}
