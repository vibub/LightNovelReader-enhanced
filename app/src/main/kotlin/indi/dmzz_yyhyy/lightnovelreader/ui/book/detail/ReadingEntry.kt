package indi.dmzz_yyhyy.lightnovelreader.ui.book.detail

internal data class ReadingEntry(
    val chapterId: String,
    val restoreProgress: Boolean
)

internal fun hasReadingRecord(lastReadChapterId: String?): Boolean =
    !lastReadChapterId.isNullOrBlank()

internal fun resolveReadingEntry(
    lastReadChapterId: String?,
    firstChapterId: String?
): ReadingEntry? {
    lastReadChapterId
        ?.takeIf(String::isNotBlank)
        ?.let { return ReadingEntry(it, restoreProgress = true) }

    return firstChapterId
        ?.takeIf(String::isNotBlank)
        ?.let { ReadingEntry(it, restoreProgress = false) }
}
