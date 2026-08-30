package indi.dmzz_yyhyy.lightnovelreader.data.download

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

class InsufficientStorageException(
    availableBytes: Long,
    requiredBytes: Long
) : IllegalStateException(
    "可用存储空间不足：当前 ${availableBytes / DownloadSettings.BYTES_PER_MB} MB，" +
        "至少需要 ${requiredBytes / DownloadSettings.BYTES_PER_MB} MB"
)

@Singleton
class DownloadStorageManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    fun availableBytes(): Long = runCatching {
        StatFs(context.filesDir.path).availableBytes
    }.getOrDefault(0L)

    fun hasEnoughSpace(requiredBytes: Long): Boolean = availableBytes() >= requiredBytes

    fun requireEnoughSpace(requiredBytes: Long) {
        val available = availableBytes()
        if (available < requiredBytes) {
            throw InsufficientStorageException(available, requiredBytes)
        }
    }

    companion object {
        fun requiredBytes(
            chapterCount: Int,
            minimumFreeStorageBytes: Long,
            estimatedBytesPerChapter: Long = 512L * 1024L
        ): Long = DownloadSettingsRepository.estimateRequiredBytes(
            chapterCount = chapterCount,
            minimumFreeStorageBytes = minimumFreeStorageBytes,
            estimatedBytesPerChapter = estimatedBytesPerChapter
        )
    }
}
