package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterDownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChapterDownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterDownloadEntity>)

    @Query(
        "select * from chapter_download_status " +
            "where source_id = :sourceId and book_id = :bookId"
    )
    suspend fun getByBook(sourceId: Int, bookId: String): List<ChapterDownloadEntity>

    @Query(
        "select * from chapter_download_status " +
            "where source_id = :sourceId and book_id = :bookId"
    )
    fun getByBookFlow(sourceId: Int, bookId: String): Flow<List<ChapterDownloadEntity>>

    @Query("select * from chapter_download_status")
    suspend fun getAll(): List<ChapterDownloadEntity>

    @Query(
        "select status from chapter_download_status where source_id = :sourceId and " +
            "book_id = :bookId and chapter_id = :chapterId"
    )
    suspend fun getStatus(sourceId: Int, bookId: String, chapterId: String): String?

    @Query(
        "delete from chapter_download_status where source_id = :sourceId and " +
            "book_id = :bookId and chapter_id = :chapterId"
    )
    suspend fun delete(sourceId: Int, bookId: String, chapterId: String)

    @Query(
        "select chapter_id from chapter_download_status " +
            "where source_id = :sourceId and book_id = :bookId " +
            "and status = 'QUEUED' order by updated_at, chapter_id"
    )
    suspend fun getQueuedChapterIds(sourceId: Int, bookId: String): List<String>

    @Query(
        "update chapter_download_status set status = 'QUEUED', error_message = null, " +
            "updated_at = :updatedAt where source_id = :sourceId and book_id = :bookId " +
            "and status = 'DOWNLOADING'"
    )
    suspend fun resetDownloading(sourceId: Int, bookId: String, updatedAt: Long)

    @Query(
        "delete from chapter_download_status where source_id = :sourceId and book_id = :bookId " +
            "and status in ('QUEUED', 'DOWNLOADING')"
    )
    suspend fun deletePending(sourceId: Int, bookId: String)

    @Query(
        "delete from chapter_download_status where source_id = :sourceId and book_id = :bookId " +
            "and chapter_id in (:chapterIds)"
    )
    suspend fun deleteByChapterIds(sourceId: Int, bookId: String, chapterIds: List<String>)

    @Query(
        "delete from chapter_download_status where source_id = :sourceId and book_id = :bookId"
    )
    suspend fun deleteByBook(sourceId: Int, bookId: String)

    @Query("delete from chapter_download_status")
    suspend fun clear()
}
