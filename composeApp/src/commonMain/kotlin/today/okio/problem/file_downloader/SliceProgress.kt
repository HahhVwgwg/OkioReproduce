package today.okio.problem.file_downloader

import today.okio.problem.file_downloader.Progress

// Progress of a slice
data class SliceProgress(val url: String, val sliceIndex: Int, val progress: Progress)
