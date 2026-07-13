package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterComment
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterCommentDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentPage
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentQuery
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibHttpException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChapterCommentsStateTest {
    @Test
    fun guestLoadsHotOnlyAndDoesNotRequestAll() = runBlocking {
        withController(FakeCommentDataSource()) { controller, dataSource ->
            controller.open(context("1"))
            controller.selectTab(ChapterCommentTab.All)

            assertEquals(listOf(LinovelibCommentQuery.Hot), dataSource.requests.map { it.query })
            assertEquals(ChapterCommentTab.All, controller.state.selectedTab)
            assertFalse(controller.state.hasCookie)
            assertEquals(listOf("hot-1"), controller.state.hotComments.map { it.id })
            assertTrue(controller.state.allComments.isEmpty())
        }
    }

    @Test
    fun savingCookieRefreshesHotAndLoadsSelectedAllTab() = runBlocking {
        withController(FakeCommentDataSource()) { controller, dataSource ->
            controller.open(context("1"))
            controller.selectTab(ChapterCommentTab.All)
            dataSource.cookie.value = "member-cookie"
            yield()

            assertEquals(
                listOf(
                    RequestRecord(LinovelibCommentQuery.Hot, 1, false, "1"),
                    RequestRecord(LinovelibCommentQuery.Hot, 1, true, "1"),
                    RequestRecord(LinovelibCommentQuery.All, 1, true, "1")
                ),
                dataSource.requests
            )
            assertTrue(controller.state.hasCookie)
            assertEquals(listOf("all-1-1"), controller.state.allComments.map { it.id })
        }
    }

    @Test
    fun allCommentsAppendDeduplicateAndStopAtLastPage() = runBlocking {
        val dataSource = FakeCommentDataSource(initialCookie = "cookie").apply {
            loader = { _, chapterId, _, query, pageIndex ->
                when {
                    query == LinovelibCommentQuery.Hot -> page(comment("hot-$chapterId"))
                    pageIndex == 1 -> page(
                        comment("a"),
                        comment("b"),
                        pageIndex = 1,
                        pageTotal = 2,
                        hasMore = true
                    )
                    else -> page(
                        comment("b"),
                        comment("c"),
                        pageIndex = 2,
                        pageTotal = 2,
                        hasMore = false
                    )
                }
            }
        }
        withController(dataSource) { controller, _ ->
            controller.open(context("1"))
            controller.selectTab(ChapterCommentTab.All)
            controller.loadNextPage()
            controller.loadNextPage()

            assertEquals(listOf("a", "b", "c"), controller.state.allComments.map { it.id })
            assertFalse(controller.state.hasMoreAll)
            assertEquals(
                listOf(1, 2),
                dataSource.requests
                    .filter { it.query == LinovelibCommentQuery.All }
                    .map { it.pageIndex }
            )
        }
    }

    @Test
    fun staleChapterResponseCannotOverwriteNewChapter() = runBlocking {
        val delayedFirst = CompletableDeferred<LinovelibCommentPage>()
        val dataSource = FakeCommentDataSource().apply {
            loader = { _, chapterId, _, _, _ ->
                if (chapterId == "1") delayedFirst.await() else page(comment("hot-$chapterId"))
            }
        }
        withController(dataSource) { controller, _ ->
            controller.open(context("1"))
            yield()
            controller.open(context("2"))
            delayedFirst.complete(page(comment("hot-1")))
            yield()

            assertEquals("2", controller.state.context?.chapterId)
            assertEquals(listOf("hot-2"), controller.state.hotComments.map { it.id })
        }
    }

    @Test
    fun cacheExpiresAtFiveMinutes() = runBlocking {
        var now = 0L
        val dataSource = FakeCommentDataSource()
        withController(dataSource, nowMillis = { now }) { controller, _ ->
            controller.open(context("1"))
            controller.dismiss()
            now = 299_999L
            controller.open(context("1"))
            assertEquals(1, dataSource.requests.size)

            controller.dismiss()
            now = 300_000L
            controller.open(context("1"))
            assertEquals(2, dataSource.requests.size)
        }
    }

    @Test
    fun cacheKeepsOnlyFiveMostRecentlyUsedChapters() = runBlocking {
        var now = 0L
        val dataSource = FakeCommentDataSource()
        withController(dataSource, nowMillis = { now }) { controller, _ ->
            (1..6).forEach { chapter ->
                now += 1
                controller.open(context(chapter.toString()))
                controller.dismiss()
            }
            controller.open(context("1"))

            assertEquals(7, dataSource.requests.size)
        }
    }

    @Test
    fun rateLimitErrorMapsWithoutExposingResponseDetails() = runBlocking {
        val dataSource = FakeCommentDataSource().apply {
            loader = { _, _, _, _, _ ->
                throw LinovelibHttpException(429, "https://example.invalid", retryAfterMillis = 1_000)
            }
        }
        withController(dataSource) { controller, _ ->
            controller.open(context("1"))

            assertEquals(ChapterCommentError.RateLimited, controller.state.hotError)
            assertFalse(controller.state.isLoadingHot)
            assertNull(controller.state.allError)
        }
    }

    private suspend fun withController(
        dataSource: FakeCommentDataSource,
        nowMillis: () -> Long = { 0L },
        block: suspend (ChapterCommentsController, FakeCommentDataSource) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = ChapterCommentsController(
                coroutineScope = scope,
                dataSource = dataSource,
                nowMillis = nowMillis
            )
            yield()
            block(controller, dataSource)
        } finally {
            scope.cancel()
        }
    }

    private fun context(chapterId: String) = ChapterEndContext(
        bookId = "8",
        chapterId = chapterId,
        chapterTitle = "章节 $chapterId",
        refererChapterPageId = "${chapterId}_5"
    )

    private fun comment(id: String) = LinovelibChapterComment(
        id = id,
        username = "用户",
        avatarUrl = "",
        userProfileUrl = "",
        publishedAt = "",
        honor = "",
        body = id,
        quotedReplies = emptyList(),
        likeCount = 0,
        dislikeCount = 0,
        isSpoiler = false
    )

    private fun page(
        vararg comments: LinovelibChapterComment,
        pageIndex: Int = 1,
        pageTotal: Int = 1,
        hasMore: Boolean = false
    ) = LinovelibCommentPage(
        comments = comments.toList(),
        totalCount = comments.size,
        participantCount = comments.size,
        pageIndex = pageIndex,
        pageTotal = pageTotal,
        hasMore = hasMore
    )

    private data class RequestRecord(
        val query: LinovelibCommentQuery,
        val pageIndex: Int,
        val hasCookie: Boolean,
        val chapterId: String
    )

    private inner class FakeCommentDataSource(
        initialCookie: String = ""
    ) : LinovelibChapterCommentDataSource {
        val cookie = MutableStateFlow(initialCookie)
        val requests = mutableListOf<RequestRecord>()
        var loader: suspend (
            bookId: String,
            chapterId: String,
            refererChapterPageId: String,
            query: LinovelibCommentQuery,
            pageIndex: Int
        ) -> LinovelibCommentPage = { _, chapterId, _, query, pageIndex ->
            page(
                comment(if (query == LinovelibCommentQuery.Hot) "hot-$chapterId" else "all-$chapterId-$pageIndex"),
                pageIndex = pageIndex
            )
        }

        override fun getCookieFlow(): Flow<String> = cookie

        override fun hasCookie(): Boolean = cookie.value.isNotBlank()

        override suspend fun loadComments(
            bookId: String,
            chapterId: String,
            refererChapterPageId: String,
            query: LinovelibCommentQuery,
            pageIndex: Int
        ): LinovelibCommentPage {
            requests += RequestRecord(query, pageIndex, hasCookie(), chapterId)
            return loader(bookId, chapterId, refererChapterPageId, query, pageIndex)
        }
    }
}
