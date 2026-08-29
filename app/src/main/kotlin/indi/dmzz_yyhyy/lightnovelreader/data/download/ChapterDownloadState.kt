package indi.dmzz_yyhyy.lightnovelreader.data.download

enum class ChapterDownloadStatus {
    NOT_DOWNLOADED,
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    PARTIAL,
    FAILED
}

data class ChapterDownloadState(
    val status: ChapterDownloadStatus,
    val errorMessage: String? = null
) {
    val isAvailableOffline: Boolean
        get() = status == ChapterDownloadStatus.COMPLETED ||
            status == ChapterDownloadStatus.PARTIAL
}
