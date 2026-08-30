package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.VolumeEntity
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume

@Dao
interface BookVolumesDao {
    @Query(
        "replace into volume " +
            "(source_id, book_id, volume_id, volume_title, chapter_id_list, volume_index) " +
            "values (-1, :bookId, :volumeId, :volumeTitle, :chapterIds, :index)"
    )
    suspend fun insertVolume(
        bookId: String,
        volumeId: String,
        volumeTitle: String,
        chapterIds: String,
        index: Int
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVolume(entity: VolumeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapterInformation(chapterInformationEntity: ChapterInformationEntity)

    @Query("select id, title from chapter_information where id = :id limit 1")
    suspend fun getChapterInformation(id: String): ChapterInformation?

    @Query(
        "select * from chapter_information " +
            "where source_id = :sourceId and book_id = :bookId and id = :id"
    )
    suspend fun getChapterInformationEntity(
        sourceId: Int,
        bookId: String,
        id: String
    ): ChapterInformationEntity?

    @Query(
        "select * from chapter_information " +
            "where source_id = :sourceId and book_id = '' and id = :id limit 1"
    )
    suspend fun getUnscopedChapterInformationEntity(
        sourceId: Int,
        id: String
    ): ChapterInformationEntity?

    @Query(
        "select * from chapter_information " +
            "where source_id = -1 and (book_id = :bookId or book_id = '') and id = :id " +
            "order by case when book_id = :bookId then 0 else 1 end limit 1"
    )
    suspend fun getLegacyChapterInformationEntity(
        bookId: String,
        id: String
    ): ChapterInformationEntity?

    @Transaction
    suspend fun insertVolume(bookId: String, volumes: BookVolumes) {
        insertVolume(VolumeEntity.LEGACY_SOURCE_ID, bookId, volumes)
    }

    @Transaction
    suspend fun insertVolume(sourceId: Int, bookId: String, volumes: BookVolumes) {
        deleteSourceBookMetadata(sourceId, bookId)
        volumes.volumes.forEachIndexed { index, volume ->
            insertVolume(
                VolumeEntity(
                    bookId = bookId,
                    volumeId = volume.volumeId,
                    volumeTitle = volume.volumeTitle,
                    chapterIds = volume.chapters.map { it.id },
                    index = index,
                    sourceId = sourceId
                )
            )
            volume.chapters.forEach {
                insertChapterInformation(
                    ChapterInformationEntity(
                        id = it.id,
                        title = it.title,
                        bookId = bookId,
                        sourceId = sourceId
                    )
                )
            }
        }
    }

    @Query("select * from volume where source_id = -1 and volume_id = :volumeId limit 1")
    suspend fun getVolumeEntity(volumeId: String): VolumeEntity?

    @Query(
        "select * from volume " +
            "where source_id = :sourceId and book_id = :bookId and volume_id = :volumeId"
    )
    suspend fun getVolumeEntity(
        sourceId: Int,
        bookId: String,
        volumeId: String
    ): VolumeEntity?

    @Query("select * from volume where book_id = :bookId")
    suspend fun getVolumeEntitiesByBookId(bookId: String): List<VolumeEntity>

    @Query(
        "select * from volume where source_id = :sourceId and book_id = :bookId"
    )
    suspend fun getVolumeEntitiesBySourceAndBook(
        sourceId: Int,
        bookId: String
    ): List<VolumeEntity>

    @Query("select * from volume where book_id in (:bookIds)")
    suspend fun getVolumeEntitiesByBookIds(bookIds: List<String>): List<VolumeEntity>

    @Transaction
    suspend fun getBookVolumes(bookId: String): BookVolumes =
        getBookVolumes(VolumeEntity.LEGACY_SOURCE_ID, bookId)

    @Transaction
    suspend fun getBookVolumes(sourceId: Int, bookId: String): BookVolumes {
        val volumeEntities = getVolumeEntitiesBySourceAndBook(sourceId, bookId).ifEmpty {
            if (sourceId == VolumeEntity.LEGACY_SOURCE_ID) emptyList()
            else getVolumeEntitiesBySourceAndBook(VolumeEntity.LEGACY_SOURCE_ID, bookId)
        }
        return BookVolumes(
            bookId,
            volumeEntities.sortedBy { it.index }.map { volumeEntity ->
                Volume(
                    volumeEntity.volumeId,
                    volumeEntity.volumeTitle,
                    volumeEntity.chapterIds.map { chapterId ->
                        getChapterInformationForSource(sourceId, bookId, chapterId)
                            ?: ChapterInformation("", "")
                    }
                )
            }
        )
    }

    @Transaction
    suspend fun getChapterInformationForSource(
        sourceId: Int,
        bookId: String,
        chapterId: String
    ): ChapterInformation? = getChapterInformationEntity(sourceId, bookId, chapterId)
        ?.toChapterInformation()
        ?: getUnscopedChapterInformationEntity(sourceId, chapterId)?.toChapterInformation()
        ?: getLegacyChapterInformationEntity(bookId, chapterId)?.toChapterInformation()

    @Query("delete from volume")
    suspend fun clearVolumes()

    @Query("delete from volume where source_id = :sourceId and book_id = :bookId")
    suspend fun deleteVolumes(sourceId: Int, bookId: String)

    @Query("delete from chapter_information where source_id = :sourceId and book_id = :bookId")
    suspend fun deleteChapterInformation(sourceId: Int, bookId: String)

    @Transaction
    suspend fun deleteSourceBookMetadata(sourceId: Int, bookId: String) {
        deleteVolumes(sourceId, bookId)
        deleteChapterInformation(sourceId, bookId)
    }

    @Query("delete from volume where book_id in (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<String>)

    @Query("delete from chapter_information")
    suspend fun clearChapterInformation()

    @Query("delete from chapter_information where id in (:chapterIds)")
    suspend fun deleteChapterInformationByIds(chapterIds: List<String>)

    @Transaction
    suspend fun clear() {
        clearVolumes()
        clearChapterInformation()
    }

    @Transaction
    suspend fun insertVolumeEntities(vararg entities: VolumeEntity) {
        for (entity in entities) {
            insertVolume(entity)
        }
    }

    @Transaction
    suspend fun insertChapterInformationEntities(vararg entities: ChapterInformationEntity) {
        for (entity in entities) {
            insertChapterInformation(entity)
        }
    }

    @Query("select * from chapter_information")
    suspend fun getAllChapterInformationEntities(): List<ChapterInformationEntity>

    @Query("select * from volume")
    suspend fun getAllVolumeEntities(): List<VolumeEntity>
}

private fun ChapterInformationEntity.toChapterInformation() = ChapterInformation(id, title)
