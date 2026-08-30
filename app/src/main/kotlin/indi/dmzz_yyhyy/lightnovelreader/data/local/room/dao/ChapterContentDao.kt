package indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.converter.JsonObjectConverter
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterContentEntity
import io.nightfish.lightnovelreader.api.book.ChapterContent
import kotlinx.serialization.json.JsonObject

@Dao
interface ChapterContentDao {
    @TypeConverters(JsonObjectConverter::class)
    @Query(
        "replace into chapter_content (source_id, book_id, id, title, content, lastChapter, nextChapter) " +
                "values (:sourceId, :bookId, :id, :title, :content, :prevChapter, :nextChapter)"
    )
    suspend fun update(
        sourceId: Int,
        bookId: String,
        id: String,
        title: String,
        content: JsonObject,
        prevChapter: String,
        nextChapter: String
    )

    @Transaction
    suspend fun update(sourceId: Int, bookId: String, chapterContent: ChapterContent) {
        update(
            sourceId,
            bookId,
            chapterContent.id,
            chapterContent.title,
            chapterContent.content,
            chapterContent.prevChapter ?: "",
            chapterContent.nextChapter ?: ""
        )
    }

    @Transaction
    suspend fun update(chapterContent: ChapterContent) {
        update(
            ChapterContentEntity.LEGACY_SOURCE_ID,
            ChapterContentEntity.LEGACY_BOOK_ID,
            chapterContent
        )
    }

    @Transaction
    suspend fun update(chapterContent: ChapterContentEntity) {
        update(
            chapterContent.sourceId,
            chapterContent.bookId,
            chapterContent.id,
            chapterContent.title,
            chapterContent.content,
            chapterContent.prevChapter,
            chapterContent.nextChapter
        )
    }

    @Query("select * from chapter_content where source_id = :sourceId and book_id = :bookId and id = :id")
    suspend fun get(sourceId: Int, bookId: String, id: String): ChapterContentEntity?

    @Query("select * from chapter_content where source_id = -1 and book_id = '' and id = :id")
    suspend fun getLegacy(id: String): ChapterContentEntity?

    @Transaction
    suspend fun getScoped(sourceId: Int, bookId: String, id: String): ChapterContentEntity? =
        get(sourceId, bookId, id) ?: getLegacy(id)

    /**
     * 在同一个事务中认领旧版 legacy 正文，避免两个数据源并发读取时各自复制同一份正文。
     * 当前数据源已有精确正文时只删除 legacy 行，不再让其他数据源复用它。
     */
    @Transaction
    suspend fun migrateLegacyCachedChapterIds(
        sourceId: Int,
        bookId: String,
        chapterIds: List<String>
    ): List<String> {
        val ids = chapterIds.map(String::trim).filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return emptyList()

        val cachedIds = ids.filter { get(sourceId, bookId, it) != null }.toMutableSet()
        if (sourceId == ChapterContentEntity.LEGACY_SOURCE_ID) return cachedIds.toList()

        val legacyEntities = ids.mapNotNull { id -> getLegacy(id) }
        legacyEntities.forEach { entity ->
            if (entity.id !in cachedIds) {
                update(entity.copy(sourceId = sourceId, bookId = bookId))
                cachedIds += entity.id
            }
        }
        if (legacyEntities.isNotEmpty()) {
            deleteLegacyByIds(legacyEntities.map { it.id })
        }
        return cachedIds.toList()
    }

    @Query(
        "select * from chapter_content where id = :id " +
                "order by case when source_id = -1 and book_id = '' then 0 else 1 end limit 1"
    )
    suspend fun get(id: String): ChapterContentEntity?

    @Query("select id from chapter_content where source_id = :sourceId and book_id = :bookId and id = :id")
    suspend fun getId(sourceId: Int, bookId: String, id: String): String?

    @Query(
        "select id from chapter_content where " +
            "(source_id = :sourceId and book_id = :bookId) or " +
            "(source_id = -1 and book_id = '')"
    )
    suspend fun getIds(sourceId: Int, bookId: String): List<String>

    @Query(
        "select id from chapter_content where " +
            "(source_id = :sourceId and book_id = :bookId) or " +
            "(source_id = -1 and book_id = '')"
    )
    fun getIdsFlow(sourceId: Int, bookId: String): kotlinx.coroutines.flow.Flow<List<String>>

    @Query("select id from chapter_content where source_id = -1 and book_id = '' and id = :id")
    suspend fun getLegacyId(id: String): String?

    @Query("select id from chapter_content where id = :id limit 1")
    suspend fun getId(id: String): String?

    @Query("delete from chapter_content")
    suspend fun clear()

    @Query("delete from chapter_content where source_id = :sourceId and book_id = :bookId and id = :id")
    suspend fun delete(sourceId: Int, bookId: String, id: String)

    @Query("delete from chapter_content where source_id = :sourceId and book_id = :bookId and id in (:ids)")
    suspend fun deleteByIds(sourceId: Int, bookId: String, ids: List<String>)

    @Query("delete from chapter_content where book_id in (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<String>)

    @Query("delete from chapter_content where source_id = -1 and book_id = '' and id in (:ids)")
    suspend fun deleteLegacyByIds(ids: List<String>)

    @Query("delete from chapter_content where book_id = :bookId and id in (:ids)")
    suspend fun deleteByBookIdAndIds(bookId: String, ids: List<String>)

    @Query("delete from chapter_content where id in (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Update
    suspend fun updateEntities(vararg entities: ChapterContentEntity)

    @Query("select * from chapter_content")
    suspend fun getAllEntities(): List<ChapterContentEntity>
}
