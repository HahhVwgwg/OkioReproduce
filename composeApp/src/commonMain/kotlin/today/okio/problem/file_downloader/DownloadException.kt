package today.okio.problem.file_downloader

class GetFileInfoFailed(message: String) : Exception(message)

class DownloadRequestFailed(message: String, internalException: Exception) : Exception(message)

class HandleFileFailed(message: String, internalException: Exception) : Exception(message)

class ValidateFileFailed(message: String) : Exception(message)