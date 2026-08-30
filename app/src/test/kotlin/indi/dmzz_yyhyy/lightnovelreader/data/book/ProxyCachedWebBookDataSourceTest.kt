package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.data.web.EmptyWebDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyCachedWebBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.proxy.ProxyWebBookDataSource
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyCachedWebBookDataSourceTest {
    @Test
    fun repeatedRequestUsesRequestKeyCache() = runBlocking {
        val source = FakeCachingSource()
        val proxy = ProxyCachedWebBookDataSource(DirectProxy(source))

        val first = proxy.getBookVolumes("2890").get()!!
        val second = proxy.getBookVolumes("2890").get()!!

        assertEquals(1, source.volumeRequestCount)
        assertEquals(first, second)
    }

    @Test
    fun differentRequestKeysDoNotShareCachedValue() = runBlocking {
        val source = FakeCachingSource()
        val proxy = ProxyCachedWebBookDataSource(DirectProxy(source))

        val first = proxy.getBookVolumes("2890").get()!!
        val second = proxy.getBookVolumes("4800").get()!!

        assertEquals(2, source.volumeRequestCount)
        assertEquals("2890", first.bookId)
        assertEquals("4800", second.bookId)
    }

    @Test
    fun chapterRequestKeyKeepsChapterAndBookIdsDistinct() = runBlocking {
        val source = FakeCachingSource()
        val proxy = ProxyCachedWebBookDataSource(DirectProxy(source))

        val first = proxy.getChapterContent("ab", "c").get()!!
        val second = proxy.getChapterContent("a", "bc").get()!!

        assertEquals("ab", first.id)
        assertEquals("a", second.id)
        assertEquals(2, source.chapterRequestCount)
    }

    private class FakeCachingSource : WebBookDataSource by EmptyWebDataSource {
        override val id = Identifier("test", "cached-source")
        override val cache = Cache(timeout = 60_000)
        var volumeRequestCount = 0
        var chapterRequestCount = 0

        override suspend fun getBookVolumes(id: String): Result<BookVolumes, WebRequestError> {
            volumeRequestCount++
            return Ok(
                BookVolumes(
                    bookId = id,
                    volumes = listOf(
                        Volume(
                            volumeId = "${id}_0",
                            volumeTitle = "正文",
                            chapters = listOf(ChapterInformation("${id}_chapter", "章节"))
                        )
                    )
                )
            )
        }

        override suspend fun getChapterContent(
            chapterId: String,
            bookId: String
        ): Result<ChapterContent, WebRequestError> {
            chapterRequestCount++
            return Ok(
                ChapterContent(
                    id = chapterId,
                    title = bookId,
                    content = kotlinx.serialization.json.JsonObject(emptyMap())
                )
            )
        }
    }

    private class DirectProxy(
        override val origin: WebBookDataSource
    ) : ProxyWebBookDataSource {
        override val proxiedWebBookDataSource: ProxyWebBookDataSource
            get() = this

        override suspend fun getBookInformation(
            id: String,
            priority: WebDataSourcePriority
        ): Result<BookInformation, WebRequestError> = origin.getBookInformation(id)

        override suspend fun getBookVolumes(
            id: String,
            priority: WebDataSourcePriority
        ): Result<BookVolumes, WebRequestError> = origin.getBookVolumes(id)

        override suspend fun getChapterContent(
            chapterId: String,
            bookId: String,
            priority: WebDataSourcePriority
        ): Result<ChapterContent, WebRequestError> = origin.getChapterContent(chapterId, bookId)
    }
}
