package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.JsonObjectConverter
import indi.dmzz_yyhyy.lightnovelreader.data.serializer.JsonObjectSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject

@Serializable(with = ChapterContentEntitySerializer::class)
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
    @ColumnInfo(name = "lastChapter")
    val prevChapter: String,
    val nextChapter: String
) : Mergeable<ChapterContentEntity> {
    override fun merge(new: ChapterContentEntity): ChapterContentEntity = new

    companion object {
        const val LEGACY_SOURCE_ID = -1
        const val LEGACY_BOOK_ID = ""
    }
}

/** 同时读取重命名前的 lastChapter 与本 fork 已导出的 prevChapter；Room 列名保持不变。 */
object ChapterContentEntitySerializer : KSerializer<ChapterContentEntity> {
    @Serializable
    private data class ArchiveEntity(
        val sourceId: Int = ChapterContentEntity.LEGACY_SOURCE_ID,
        val bookId: String = ChapterContentEntity.LEGACY_BOOK_ID,
        val id: String,
        val title: String,
        @Serializable(JsonObjectSerializer::class)
        val content: JsonObject,
        val prevChapter: String? = null,
        val lastChapter: String? = null,
        val nextChapter: String
    )

    override val descriptor = ArchiveEntity.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ChapterContentEntity) {
        encoder.encodeSerializableValue(ArchiveEntity.serializer(), ArchiveEntity(
            sourceId = value.sourceId,
            bookId = value.bookId,
            id = value.id,
            title = value.title,
            content = value.content,
            prevChapter = value.prevChapter,
            nextChapter = value.nextChapter
        ))
    }

    override fun deserialize(decoder: Decoder): ChapterContentEntity {
        val value = decoder.decodeSerializableValue(ArchiveEntity.serializer())
        return ChapterContentEntity(
            sourceId = value.sourceId,
            bookId = value.bookId,
            id = value.id,
            title = value.title,
            content = value.content,
            prevChapter = value.prevChapter ?: value.lastChapter
                ?: throw SerializationException("章节缓存缺少 prevChapter/lastChapter"),
            nextChapter = value.nextChapter
        )
    }
}
