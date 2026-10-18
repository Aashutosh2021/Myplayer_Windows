package coil.disk

import java.io.File

class DiskCache {
    class Builder {
        fun directory(dir: File): Builder = this
        fun maxSizeBytes(bytes: Long): Builder = this
        fun build(): DiskCache = DiskCache()
    }
}
