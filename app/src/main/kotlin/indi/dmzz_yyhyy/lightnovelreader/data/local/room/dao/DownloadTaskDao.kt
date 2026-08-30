package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DownloadTaskEntity

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadTaskEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DownloadTaskEntity>)

    @Query("select * from download_task where source_id = :sourceId and book_id = :bookId")
    suspend fun get(sourceId: Int, bookId: String): DownloadTaskEntity?

    @Query("select * from download_task")
    suspend fun getAll(): List<DownloadTaskEntity>

    @Query("delete from download_task where source_id = :sourceId and book_id = :bookId")
    suspend fun delete(sourceId: Int, bookId: String)

    @Query("delete from download_task")
    suspend fun clear()
}
