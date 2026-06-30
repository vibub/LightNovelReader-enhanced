package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.JsonObjectConverter
import indi.dmzz_yyhyy.lightnovelreader.data.serializer.JsonObjectSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@TypeConverters(
    JsonObjectConverter::class
)
@Entity(
    tableName = "chapter_content",
    primaryKeys = ["source_id", "book_id", "id"],
    indices = [
        Index(value = ["id"]),
        Index(value = ["source_id", "book_id"])
    ]
)
data class ChapterContentEntity(
    @ColumnInfo(name = "source_id")
    val sourceId: Int = LEGACY_SOURCE_ID,
    @ColumnInfo(name = "book_id")
    val bookId: String = LEGACY_BOOK_ID,
    val id: String,
    val title: String,
    @Serializable(JsonObjectSerializer::class)
    val content: JsonObject,
    val lastChapter: String,
    val nextChapter: String
): Mergeable<ChapterContentEntity> {
    override fun merge(new: ChapterContentEntity): ChapterContentEntity = new

    companion object {
        const val LEGACY_SOURCE_ID = -1
        const val LEGACY_BOOK_ID = ""
    }
}
