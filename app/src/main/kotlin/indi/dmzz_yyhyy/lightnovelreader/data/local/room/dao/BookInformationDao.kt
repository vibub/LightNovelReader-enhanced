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
        insert(
            BookInformationEntity(
                id = information.id,
                title = information.title,
                subtitle = information.subtitle,
                coverUri = information.coverUri,
                author = information.author,
                description = information.description,
                tags = information.tags,
                publishingHouse = information.publishingHouse,
                wordCount = information.wordCount,
                lastUpdated = information.lastUpdated,
                isComplete = information.isComplete
            )
        )
    }

    @Query("select * from book_information where id=:id")
    suspend fun getEntity(id: String): BookInformationEntity?

    @Query("select * from book_information")
    suspend fun getAllEntities(): List<BookInformationEntity>

    @Query("select * from book_information where id in (:ids)")
    suspend fun getEntities(ids: List<String>): List<BookInformationEntity>

    @Query("delete from book_information where id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun get(id: String): BookInformation? = getEntity(id)?.toBookInformation()

    @Transaction
    suspend fun getByIds(ids: List<String>): List<BookInformation> =
        if (ids.isEmpty()) emptyList()
        else getEntities(ids).map(BookInformationEntity::toBookInformation)

    @Transaction
    suspend fun has(id: String): Boolean {
        return get(id) != null
    }

    @Query("delete from book_information")
    suspend fun clear()
}

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
