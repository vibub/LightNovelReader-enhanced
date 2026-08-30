package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookInformationEntity
import io.nightfish.lightnovelreader.api.book.BookInformation

@Dao
interface BookInformationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(information: BookInformationEntity)

    @Transaction
    suspend fun insert(information: BookInformation) {
        insert(information.toEntity(BookInformationEntity.LEGACY_SOURCE_ID))
    }

    @Transaction
    suspend fun insertForSource(sourceId: Int, information: BookInformation) {
        insert(information.toEntity(sourceId))
    }

    @Query(
        "select * from book_information where id = :id " +
            "order by case when source_id = -1 then 0 else 1 end, last_update desc limit 1"
    )
    suspend fun getEntity(id: String): BookInformationEntity?

    @Query("select * from book_information where source_id = :sourceId and id = :id")
    suspend fun getEntityForSource(sourceId: Int, id: String): BookInformationEntity?

    @Query("select * from book_information")
    suspend fun getAllEntities(): List<BookInformationEntity>

    @Query("select * from book_information where id in (:ids)")
    suspend fun getEntities(ids: List<String>): List<BookInformationEntity>

    @Query("select * from book_information where source_id = :sourceId and id in (:ids)")
    suspend fun getEntitiesForSource(sourceId: Int, ids: List<String>): List<BookInformationEntity>

    @Query("delete from book_information where id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("delete from book_information where source_id = :sourceId and id in (:ids)")
    suspend fun deleteBySource(sourceId: Int, ids: List<String>)

    @Transaction
    suspend fun get(id: String): BookInformation? = getEntity(id)?.toBookInformation()

    @Transaction
    suspend fun getForSource(sourceId: Int, id: String): BookInformation? =
        getEntityForSource(sourceId, id)?.toBookInformation()
            ?: if (sourceId == BookInformationEntity.LEGACY_SOURCE_ID) null
            else getEntityForSource(BookInformationEntity.LEGACY_SOURCE_ID, id)?.toBookInformation()

    @Transaction
    suspend fun getByIds(ids: List<String>): List<BookInformation> =
        if (ids.isEmpty()) emptyList()
        else getEntities(ids).map(BookInformationEntity::toBookInformation)

    @Transaction
    suspend fun getByIdsForSource(sourceId: Int, ids: List<String>): List<BookInformation> {
        if (ids.isEmpty()) return emptyList()
        val exact = getEntitiesForSource(sourceId, ids)
        if (sourceId == BookInformationEntity.LEGACY_SOURCE_ID) {
            return exact.map(BookInformationEntity::toBookInformation)
        }
        val exactIds = exact.mapTo(mutableSetOf()) { it.id }
        val missingIds = ids.filterNot(exactIds::contains)
        val legacy = if (missingIds.isEmpty()) emptyList()
        else getEntitiesForSource(BookInformationEntity.LEGACY_SOURCE_ID, missingIds)
        return (exact + legacy).map(BookInformationEntity::toBookInformation)
    }

    @Transaction
    suspend fun has(id: String): Boolean {
        return get(id) != null
    }

    @Query("delete from book_information")
    suspend fun clear()
}

private fun BookInformation.toEntity(sourceId: Int) = BookInformationEntity(
    id = id,
    title = title,
    subtitle = subtitle,
    coverUri = coverUri,
    author = author,
    description = description,
    tags = tags,
    publishingHouse = publishingHouse,
    wordCount = wordCount,
    lastUpdated = lastUpdated,
    isComplete = isComplete,
    sourceId = sourceId
)

private fun BookInformationEntity.toBookInformation() = BookInformation(
    id,
    title,
    subtitle,
    coverUri,
    author,
    description,
    tags,
    publishingHouse,
    wordCount,
    lastUpdated,
    isComplete,
)
