package today.okio.problem.file_downloader

import okio.FileSystem

actual val FILESYSTEM: FileSystem
    get() = FileSystem.SYSTEM