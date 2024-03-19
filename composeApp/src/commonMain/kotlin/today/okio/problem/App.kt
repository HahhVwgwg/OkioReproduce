package today.okio.problem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import today.okio.problem.file_downloader.DownloadResult
import today.okio.problem.file_downloader.FileDownloader
import today.okio.problem.theme.AppTheme

@Composable
internal fun App() = AppTheme {
    LaunchedEffect(Unit) {
        val httpClient = HttpClient()
        val downloader = FileDownloader(httpClient)

        downloader.downloadFile(
            this,
            url = "https://android.quran.com/data/getTranslation.php?id=54",
            fileName = "hey.db"
        ).catch {
            it.printStackTrace()
        }.collectLatest { downloadResult ->
            when (downloadResult) {
                is DownloadResult.Single.DownloadCompleted -> {
                    println("DownloadSingleCompleted")
                    println(downloadResult.filePath)
                }
                is DownloadResult.Single.DownloadFailed -> {
                    println("DownloadFailed")
                }
                is DownloadResult.Single.ProgressUpdate -> {
                    println("ProgressUpdate")
                }
                is DownloadResult.Batch.DownloadCompleted -> {
                    println(downloadResult.urls)
                    println("DownloadBatchCompleted")
                }
                is DownloadResult.Batch.DownloadFailed -> {
                    println("DownloadFailed")
                }
                is DownloadResult.Batch.ProgressUpdate -> {
                    println("ProgressUpdate")
                }
            }
        }
    }
}

internal expect fun openUrl(url: String?)