package today.okio.problem.file_downloader

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

class FileDownloader(private val client: HttpClient) {

    private val maxThreadCount: Int = 5
    private val validator: FileValidator? = null

    // Get file info, including size and whether it supports range requests or not
    private suspend fun getFileInfo(url: String): FileInfo {
        println("FileDownloader getFileInfo url = $url \n")
        val response = client.head(url)

        println("FileDownloader getFileInfo response = $response \n")
        if (!response.status.isSuccess()) {
            println("FileDownloader getFileInfo request failed url= $url \n")
            throw GetFileInfoFailed("get file info request failed!")
        }
        println("FileDownloader getFileInfo request success url= $url \n")
        val fileSize = response.contentLength()
            ?: kotlin.run { throw GetFileInfoFailed("contentLength not found") }
        return FileInfo(fileSize, false)
    }

    fun downloadFile(
        screenModelScope: CoroutineScope,
        url: String,
        fileName: String,
        fileInfo: FileInfo? = null
    ): Flow<DownloadResult> = callbackFlow {
        val outputPath = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString() + fileName

        screenModelScope.launch {
            try {
                println("FileDownloader start download url = $url \n")
                // Get the target file info. If fileInfo is null, fetch it from the URL
                val targetFileInfo = fileInfo ?: getFileInfo(url)
                val tempPath = createUrlFolder(
                    outputPath.toPath(),
                    url
                )

                println("FileDownloader tempPath = $tempPath \n")
                val f = MutableSharedFlow<SliceProgress>(
                    extraBufferCapacity = 100,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )

                println("FileDownloader MutableSharedFlow = $f \n")

                val flow = ThrottledFlow(f, 200)

                // Initialize threadCount as 1
                var threadCount = 1
                var downloads = emptyList<Deferred<*>>()

                println("FileDownloader start download url = $url \n")

                // If the target file does not support range requests, download it in a single thread
                if (!targetFileInfo.acceptRange) {
                    println("FileDownloader downloadFileNormally url = $url \n")
                    val filePath = tempPath.resolve("0.part")
                    downloadFileNormally(
                        url = url,
                        fileSize = targetFileInfo.fileSize,
                        tempPath = filePath,
                        progressFlow = flow
                    )
                }

                // Wait for all parts to be downloaded
                downloads.awaitAll()

                println("FileDownloader downloaded to temp directory start moving url = $url \n")

                // Merge all parts into one file if downloaded in multiple threads
                if (threadCount > 1) {
                    mergeFileParts(url, tempPath, outputPath.toPath(), threadCount)
                } else {
                    val tempFilePath = tempPath.resolve("0.part")
                    safeMoveFile(url, tempFilePath, outputPath.toPath())
                }

                // Verify downloaded file if validator is provided
                validator?.let {
                    if (!it.validateFileWithDigest(outputPath))
                        send(
                            DownloadResult.Single.DownloadFailed(
                                ValidateFileFailed("FileDownloader validate file failed"),
                                url
                            )
                        )
                }

                println("FileDownloader download success url= $url \n")

                // If no exception occurred, send DownloadCompleted event
                send(DownloadResult.Single.DownloadCompleted(outputPath, url))
            } finally {
                // Close the channel when done
                channel.close()
            }
        }

        // Wait for the download to complete
        awaitClose {
            println("FileDownloader awaitClose")
        }
    }

    // Download a file in a single thread
    private suspend fun downloadFileNormally(
        url: String,
        fileSize: Long,
        tempPath: Path,
        progressFlow: MutableSharedFlow<SliceProgress>
    ) {
        // Check if the temp file already exists and has correct size
        val existSize = checkTempFile(tempPath)
        if (existSize != fileSize) {
            // If not, delete the temp file
            FILESYSTEM.delete(tempPath, mustExist = false)
            println("FileDownloader delete temp file url= $tempPath \n")
            val request = client.prepareGet(url) {}
            println("FileDownloader after prepareGet ")

            val fileHandle =
                FILESYSTEM.openReadWrite(tempPath, mustCreate = false, mustExist = false)

            println("FileDownloader openReadWrite temp file url= $url \n")

            fileHandle.use { handle ->
                var bytesReadTotal = 0L
                request.execute { it ->
                    val bytes = ByteArray(DEFAULT_CHUNK_SIZE)
                    var bytesRead: Int
                    val channel: ByteReadChannel = it.body()

                    while (channel.readAvailable(bytes).also { bytesRead = it } != -1) {
                        handle.write(bytesReadTotal, bytes, 0, bytesRead)
                        bytesReadTotal += bytesRead
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 8192
    }
}

// Check if the temp file exists and return its size
private fun checkTempFile(tempFilePath: Path): Long {
    return FILESYSTEM.takeIf { it.exists(tempFilePath) }?.run {
        metadataOrNull(tempFilePath)?.size ?: 0
    } ?: 0
}

// Create a folder for the temp file
private fun createUrlFolder(outputPath: Path, url: String): Path {
    println("FileDownloader createUrlFolder outputPath = $outputPath, url = $url \n")
    val parentPath = outputPath.parent ?: outputPath

    println("FileDownloader createUrlFolder resultPath = $parentPath \n")
    // If the folder does not exist, create it
    if (!FILESYSTEM.exists(parentPath)) {
        println("FileDownloader createUrlFolder createDirectories resultPath = $parentPath \n")
        FILESYSTEM.createDirectories(parentPath, false)
    }
    println("FileDownloader createUrlFolder resultPath = $parentPath \n")
    return parentPath
}

// Merge all parts into a single file
private fun mergeFileParts(url: String, tempPath: Path, outputPath: Path, threadCount: Int) {
    try {
        // Delete the output file if it already exists
        FILESYSTEM.delete(path = outputPath, mustExist = false)

        // Open the output file
        FILESYSTEM.sink(outputPath).buffer().use { output ->
            try {
                for (index in 0 until threadCount) {
                    val tPath = tempPath.resolve("$index.part")
                    // Check if the part file exists; it might not exist if the file size is smaller than expected
                    if (FILESYSTEM.exists(tPath)) {
                        val metadata = FILESYSTEM.metadata(tPath)
                        val partSize = metadata.size

                        // Read from the part file and write to the output file
                        FILESYSTEM.source(tPath).buffer().use { input ->
                            val size = input.readAll(output)
                            println("FileDownloader readAll from path = $tPath, size = $size \n")
                        }
                    }
                }

                // Delete all parts
                FILESYSTEM.deleteRecursively(tempPath, mustExist = false)
            } catch (e: Exception) {
                throw HandleFileFailed(
                    "merge tempFile to output path failed exception = ${e.message}",
                    e
                )
            }
        }
    } catch (e: Exception) {
        throw HandleFileFailed(
            "merge tempFile to output path failed exception = ${e.message}",
            e
        )
    }
}

// Move temp file to final location
private fun safeMoveFile(url: String, tempPath: Path, outputPath: Path) {
    try {
        println("FileDownloader safeMoveFile url = $url, tempPath = $tempPath, outputPath = $outputPath \n")
        FILESYSTEM.atomicMove(tempPath, outputPath)
    } catch (e: Exception) {
        throw HandleFileFailed(
            "move tempFile to output path failed exception = ${e.message}",
            e
        )
    }
}