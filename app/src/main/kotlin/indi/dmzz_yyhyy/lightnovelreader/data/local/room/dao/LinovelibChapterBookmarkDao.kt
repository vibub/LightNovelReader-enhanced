package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.TypeConverters
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.LocalDateTimeConverter
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.LinovelibChapterBookmarkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LinovelibChapterBookmarkDao {
    @TypeConverters(LocalDateTimeConverter::class)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: LinovelibChapterBookmarkEntity)

    @Query("select * from linovelib_chapter_bookmark where book_id = :bookId")
    suspend fun get(bookId: String): LinovelibChapterBookmarkEntity?

    @Query("select * from linovelib_chapter_bookmark where book_id = :bookId")
    fun getFlow(bookId: String): Flow<LinovelibChapterBookmarkEntity?>

    @Query("select * from linovelib_chapter_bookmark")
    suspend fun getAll(): List<LinovelibChapterBookmarkEntity>

    @Query("delete from linovelib_chapter_bookmark where book_id = :bookId")
    suspend fun delete(bookId: String)
}
