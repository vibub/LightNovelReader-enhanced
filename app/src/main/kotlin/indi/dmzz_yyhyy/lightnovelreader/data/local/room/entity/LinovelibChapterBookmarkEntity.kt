package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.LocalDateTimeConverter
import java.time.LocalDateTime

@TypeConverters(LocalDateTimeConverter::class)
@Entity(tableName = "linovelib_chapter_bookmark")
data class LinovelibChapterBookmarkEntity(
    @PrimaryKey
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_id")
    val chapterId: String,
    @ColumnInfo(name = "chapter_title")
    val chapterTitle: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: LocalDateTime,
    @ColumnInfo(name = "remote_updated_at")
    val remoteUpdatedAt: LocalDateTime?,
    @ColumnInfo(name = "sync_state")
    val syncState: String
)
