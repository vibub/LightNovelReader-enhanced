package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "download_task",
    primaryKeys = ["source_id", "book_id"],
    indices = [Index(value = ["state"])]
)
data class DownloadTaskEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: Int,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "source_key")
    val sourceKey: String = "",
    @ColumnInfo(name = "queue_all")
    val queueAll: Boolean = false,
    @ColumnInfo(name = "constraints_key")
    val constraintsKey: String = "",
    val state: String,
    val progress: Float = 0f,
    val total: Int = 0,
    val processed: Int = 0,
    @ColumnInfo(name = "current_chapter_id")
    val currentChapterId: String? = null,
    @ColumnInfo(name = "current_chapter_title")
    val currentChapterTitle: String? = null,
    @ColumnInfo(name = "estimated_bytes")
    val estimatedBytes: Long = 0L,
    @ColumnInfo(name = "written_bytes")
    val writtenBytes: Long = 0L,
    @ColumnInfo(name = "waiting_reason")
    val waitingReason: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
