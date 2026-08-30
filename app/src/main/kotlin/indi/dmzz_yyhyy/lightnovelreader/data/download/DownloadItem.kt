package indi.dmzz_yyhyy.lightnovelreader.data.download

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.michaelbull.result.Result
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.error.WebRequestError
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

enum class DownloadItemState {
    RUNNING,
    PAUSED,
    FAILED,
    COMPLETED
}

interface DownloadItem {
    val type: DownloadType
    val bookId: String
    /** 用于区分不同数据源中可能相同的书籍 ID。 */
    val sourceId: Int get() = 0
    /** 用于恢复后台任务时定位原始数据源。 */
    val sourceKey: String get() = ""
    val startTime: LocalDateTime
    var progress: Float
    var state: DownloadItemState
    var estimatedBytes: Long
    var writtenBytes: Long
    var currentChapterTitle: String?
    var waitingReason: String?
    var errorMessage: String?
    val bookInformationFlow: Flow<Result<BookInformation, WebRequestError>>
}

@Stable
class MutableDownloadItem(
    override val type: DownloadType,
    override val bookId: String,
    override val bookInformationFlow: Flow<Result<BookInformation, WebRequestError>>,
    override val startTime: LocalDateTime = LocalDateTime.now(),
    override val sourceId: Int = 0,
    override val sourceKey: String = ""
): DownloadItem {
    override var progress by mutableFloatStateOf(0f)
    override var state by mutableStateOf(DownloadItemState.RUNNING)
    override var estimatedBytes by mutableStateOf(0L)
    override var writtenBytes by mutableStateOf(0L)
    override var currentChapterTitle by mutableStateOf<String?>(null)
    override var waitingReason by mutableStateOf<String?>(null)
    override var errorMessage by mutableStateOf<String?>(null)

    override fun equals(other: Any?): Boolean {
        return if (other is DownloadItem) {
            other.type == type &&
                other.sourceId == sourceId &&
                other.sourceKey == sourceKey &&
                other.bookId == bookId
        } else {
            super.equals(other)
        }
    }

    override fun toString(): String {
        return "MutableDownloadItem{type=$type, sourceId=$sourceId, sourceKey=$sourceKey, bookId=$bookId, startTimeMillis=$startTime, progress=$progress}"
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + sourceId
        result = 31 * result + sourceKey.hashCode()
        result = 31 * result + bookId.hashCode()
        return result
    }
}
