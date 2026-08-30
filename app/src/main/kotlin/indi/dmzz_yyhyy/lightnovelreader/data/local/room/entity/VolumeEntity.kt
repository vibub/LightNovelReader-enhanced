package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.ListConverter
import kotlinx.serialization.Serializable

@Serializable
@TypeConverters(ListConverter::class)
@Entity(
    tableName = "volume",
    primaryKeys = ["source_id", "book_id", "volume_id"]
)
data class VolumeEntity(
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "volume_title")
    val volumeTitle: String,
    @ColumnInfo(name = "chapter_id_list")
    val chapterIds: List<String>,
    @ColumnInfo(name = "volume_index")
    val index: Int,
    @ColumnInfo(name = "source_id", defaultValue = "-1")
    val sourceId: Int = LEGACY_SOURCE_ID
): Mergeable<VolumeEntity> {
    override fun merge(new: VolumeEntity): VolumeEntity = new

    companion object {
        const val LEGACY_SOURCE_ID = -1
    }
}
