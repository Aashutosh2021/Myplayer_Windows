package android.os

import java.io.Closeable
import java.io.File
import java.io.FileDescriptor

class ParcelFileDescriptor(val file: File) : Closeable {
    val statSize: Long get() = file.length()
    val fileDescriptor: FileDescriptor get() = FileDescriptor()
    override fun close() {}

    companion object {
        const val MODE_READ_ONLY = 0x10000000
    }
}
