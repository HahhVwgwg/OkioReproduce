package today.okio.problem.file_downloader

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

@OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
val NSFileManager.DocumentDirectory: NSURL?
    get() = URLForDirectory(
        directory = NSDocumentDirectory,
        appropriateForURL = null,
        create = false,
        inDomain = NSUserDomainMask,
        error = null
    )

@OptIn(UnsafeNumber::class, ExperimentalForeignApi::class)
val NSFileManager.CachesDirectory: NSURL?
    get() = URLForDirectory(
        directory = NSCachesDirectory,
        appropriateForURL = null,
        create = false,
        inDomain = NSUserDomainMask,
        error = null
    )