package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chapter_download_status",
    primaryKeys = ["source_id", "book_id", "chapter_id"],
    indices = [Index(value = ["source_id", "book_id"])]
)
data class ChapterDownloadEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: Int,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    val status: String,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
