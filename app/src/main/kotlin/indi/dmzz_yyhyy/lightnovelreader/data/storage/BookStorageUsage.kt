package indi.dmzz_yyhyy.lightnovelreader.data.storage

import kotlinx.serialization.Serializable

@Serializable
data class BookStorageUsage(
    val bookId: String,
    val bookInformationBytes: Long,
    val volumeBytes: Long,
    val chapterInformationBytes: Long,
    val chapterContentBytes: Long,
    /** filesDir 中按书籍保存的封面和章节图片大小。 */
    val offlineContentBytes: Long = 0L,
) {
    val totalBytes: Long
        get() = bookInformationBytes + volumeBytes + chapterInformationBytes +
            chapterContentBytes + offlineContentBytes
}