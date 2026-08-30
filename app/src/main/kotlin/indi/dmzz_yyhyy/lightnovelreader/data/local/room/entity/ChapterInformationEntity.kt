package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "chapter_information",
    primaryKeys = ["source_id", "book_id", "id"],
    indices = [Index(value = ["id"])]
)
data class ChapterInformationEntity(
    val id: String,
    val title: String,
    @ColumnInfo(name = "book_id", defaultValue = "''")
    val bookId: String = "",
    @ColumnInfo(name = "source_id", defaultValue = "-1")
    val sourceId: Int = LEGACY_SOURCE_ID
): Mergeable<ChapterInformationEntity> {
    override fun merge(new: ChapterInformationEntity): ChapterInformationEntity = new

    companion object {
        const val LEGACY_SOURCE_ID = -1
    }
}
