package android.content.res

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

class AssetFileDescriptor(val file: File) : Closeable {
    val length: Long get() = file.length()
    val fileDescriptor: FileDescriptor get() = FileDescriptor()
    override fun close() {}
}
