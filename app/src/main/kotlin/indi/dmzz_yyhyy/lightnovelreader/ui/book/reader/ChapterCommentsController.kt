package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterComment
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterCommentDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentLoginRequiredException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentProtocolException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentQuery
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

internal class ChapterCommentsController(
    private val coroutineScope: CoroutineScope,
    private val dataSource: LinovelibChapterCommentDataSource,
    private val nowMillis: () -> Long,
    private val onStateChanged: (ChapterCommentsUiState) -> Unit = {}
) {
    var state by mutableStateOf(
        ChapterCommentsUiState(hasCookie = dataSource.hasCookie())
    )
        private set

    private var cookieSessionId = 0
    private var lastCookie: String? = null
    private var hotJob: Job? = null
    private var allJob: Job? = null
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
            size > MAX_CACHED_CHAPTERS
    }

    init {
        coroutineScope.launch {
            dataSource.getCookieFlow().collectLatest(::onCookieChanged)
        }
    }

    fun open(context: ChapterEndContext) {
        if (state.isVisible && state.context == context) return
        if (state.context != context) {
            hotJob?.cancel()
            allJob?.cancel()
        }
        val entry = freshCacheEntry(context)
        updateState {
            it.copy(
                isVisible = true,
                context = context,
                selectedTab = ChapterCommentTab.Hot,
                hotComments = entry.hotComments,
                allComments = entry.allComments,
                totalCount = entry.totalCount,
                participantCount = entry.participantCount,
                hasCookie = dataSource.hasCookie(),
                cookieExpired = false,
                isLoadingHot = false,
                isLoadingAll = false,
                hasMoreAll = entry.hasMoreAll,
                nextAllPage = entry.nextAllPage,
                hotError = null,
                allError = null
            )
        }
        if (!entry.hotLoaded) loadHotComments(context)
    }

    fun dismiss() {
        updateState { it.copy(isVisible = false) }
    }

    fun selectTab(tab: ChapterCommentTab) {
        if (state.selectedTab == tab) return
        updateState { it.copy(selectedTab = tab) }
        if (tab != ChapterCommentTab.All || !state.hasCookie || state.cookieExpired) return
        val context = state.context ?: return
        val entry = freshCacheEntry(context)
        if (!entry.allLoaded) loadAllComments(context, pageIndex = 1)
    }

    fun loadNextPage() {
        if (!state.hasCookie || state.cookieExpired || state.isLoadingAll || !state.hasMoreAll) return
        val context = state.context ?: return
        loadAllComments(context, state.nextAllPage)
    }

    fun retryHot() {
        val context = state.context ?: return
        loadHotComments(context, force = true)
    }

    fun retryAll() {
        if (!state.hasCookie || state.cookieExpired) return
        val context = state.context ?: return
        loadAllComments(context, state.nextAllPage.coerceAtLeast(1), force = true)
    }

    private fun loadHotComments(context: ChapterEndContext, force: Boolean = false) {
        val entry = freshCacheEntry(context)
        if (!force && entry.hotLoaded) return
        if (hotJob?.isActive == true) return
        val sessionId = cookieSessionId
        val key = context.cacheKey(sessionId)
        updateState { it.copy(isLoadingHot = true, hotError = null) }
        hotJob = coroutineScope.launch {
            val runningJob = currentCoroutineContext()[Job]
            try {
                val page = dataSource.loadComments(
                    bookId = context.bookId,
                    chapterId = context.chapterId,
                    refererChapterPageId = context.refererChapterPageId,
                    query = LinovelibCommentQuery.Hot,
                    pageIndex = 1
                )
                if (!matches(context, sessionId)) return@launch
                val currentEntry = cache[key] ?: CacheEntry()
                currentEntry.hotComments = page.comments
                currentEntry.hotLoaded = true
                currentEntry.totalCount = page.totalCount
                currentEntry.participantCount = page.participantCount
                currentEntry.lastLoadedAtMillis = nowMillis()
                cache[key] = currentEntry
                updateState {
                    it.copy(
                        hotComments = page.comments,
                        totalCount = page.totalCount,
                        participantCount = page.participantCount,
                        cookieExpired = false,
                        hotError = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (matches(context, sessionId)) {
                    val error = e.toChapterCommentError()
                    updateState {
                        it.copy(
                            cookieExpired = it.cookieExpired || error == ChapterCommentError.LoginRequired,
                            hotError = error
                        )
                    }
                }
            } finally {
                if (matches(context, sessionId)) {
                    updateState { it.copy(isLoadingHot = false) }
                }
                if (hotJob === runningJob) hotJob = null
            }
        }
    }

    private fun loadAllComments(
        context: ChapterEndContext,
        pageIndex: Int,
        force: Boolean = false
    ) {
        if (!dataSource.hasCookie()) return
        val entry = freshCacheEntry(context)
        if (!force && pageIndex == 1 && entry.allLoaded) return
        if (allJob?.isActive == true) return
        val sessionId = cookieSessionId
        val key = context.cacheKey(sessionId)
        val requestedPage = pageIndex.coerceAtLeast(1)
        updateState { it.copy(isLoadingAll = true, allError = null) }
        allJob = coroutineScope.launch {
            val runningJob = currentCoroutineContext()[Job]
            try {
                val page = dataSource.loadComments(
                    bookId = context.bookId,
                    chapterId = context.chapterId,
                    refererChapterPageId = context.refererChapterPageId,
                    query = LinovelibCommentQuery.All,
                    pageIndex = requestedPage
                )
                if (!matches(context, sessionId)) return@launch
                val currentEntry = cache[key] ?: CacheEntry()
                val previousComments = if (requestedPage == 1) emptyList() else currentEntry.allComments
                val previousIds = previousComments.asSequence().map { it.id }.toMutableSet()
                val uniqueComments = page.comments.filter { previousIds.add(it.id) }
                val mergedComments = previousComments + uniqueComments
                val hasMore = page.hasMore &&
                    page.pageIndex < page.pageTotal &&
                    page.comments.isNotEmpty() &&
                    uniqueComments.isNotEmpty()
                currentEntry.allComments = mergedComments
                currentEntry.allLoaded = true
                currentEntry.hasMoreAll = hasMore
                currentEntry.nextAllPage = page.pageIndex + 1
                currentEntry.totalCount = page.totalCount
                currentEntry.participantCount = page.participantCount
                currentEntry.lastLoadedAtMillis = nowMillis()
                cache[key] = currentEntry
                updateState {
                    it.copy(
                        allComments = mergedComments,
                        totalCount = page.totalCount,
                        participantCount = page.participantCount,
                        hasMoreAll = hasMore,
                        nextAllPage = page.pageIndex + 1,
                        cookieExpired = false,
                        allError = null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (matches(context, sessionId)) {
                    val error = e.toChapterCommentError()
                    updateState {
                        it.copy(
                            cookieExpired = it.cookieExpired || error == ChapterCommentError.LoginRequired,
                            allError = error
                        )
                    }
                }
            } finally {
                if (matches(context, sessionId)) {
                    updateState { it.copy(isLoadingAll = false) }
                }
                if (allJob === runningJob) allJob = null
            }
        }
    }

    private fun onCookieChanged(cookie: String) {
        val previousCookie = lastCookie
        lastCookie = cookie
        if (previousCookie == null) {
            updateState { it.copy(hasCookie = cookie.isNotBlank()) }
            return
        }
        if (previousCookie == cookie) return

        hotJob?.cancel()
        allJob?.cancel()
        cookieSessionId++
        cache.clear()
        val context = state.context
        val selectedTab = state.selectedTab
        updateState {
            it.copy(
                hotComments = emptyList(),
                allComments = emptyList(),
                totalCount = null,
                participantCount = null,
                hasCookie = cookie.isNotBlank(),
                cookieExpired = false,
                isLoadingHot = false,
                isLoadingAll = false,
                hasMoreAll = false,
                nextAllPage = 1,
                hotError = null,
                allError = null
            )
        }
        if (!state.isVisible || context == null) return
        loadHotComments(context, force = true)
        if (selectedTab == ChapterCommentTab.All && cookie.isNotBlank()) {
            loadAllComments(context, pageIndex = 1, force = true)
        }
    }

    private fun freshCacheEntry(context: ChapterEndContext): CacheEntry {
        val key = context.cacheKey(cookieSessionId)
        val cached = cache[key]
        if (cached != null && !cached.isExpired(nowMillis())) return cached
        if (cached != null) cache.remove(key)
        return CacheEntry().also { cache[key] = it }
    }

    private fun matches(context: ChapterEndContext, sessionId: Int): Boolean =
        state.context == context && cookieSessionId == sessionId

    private fun updateState(block: (ChapterCommentsUiState) -> ChapterCommentsUiState) {
        state = block(state)
        onStateChanged(state)
    }

    private fun Throwable.toChapterCommentError(): ChapterCommentError = when (this) {
        is LinovelibCommentLoginRequiredException -> ChapterCommentError.LoginRequired
        is LinovelibBlockedException -> ChapterCommentError.Cloudflare
        is LinovelibHttpException -> if (statusCode == 429) {
            ChapterCommentError.RateLimited
        } else {
            ChapterCommentError.Network
        }
        is IOException -> ChapterCommentError.Network
        is LinovelibCommentProtocolException -> ChapterCommentError.Protocol
        else -> ChapterCommentError.Protocol
    }

    private data class CacheKey(
        val bookId: String,
        val chapterId: String,
        val cookieSessionId: Int
    )

    private class CacheEntry {
        var hotComments: List<LinovelibChapterComment> = emptyList()
        var allComments: List<LinovelibChapterComment> = emptyList()
        var totalCount: Int? = null
        var participantCount: Int? = null
        var hotLoaded: Boolean = false
        var allLoaded: Boolean = false
        var hasMoreAll: Boolean = false
        var nextAllPage: Int = 1
        var lastLoadedAtMillis: Long = 0L

        fun isExpired(nowMillis: Long): Boolean =
            (hotLoaded || allLoaded) && nowMillis - lastLoadedAtMillis >= CACHE_TTL_MILLIS
    }

    private fun ChapterEndContext.cacheKey(sessionId: Int) = CacheKey(
        bookId = bookId,
        chapterId = chapterId,
        cookieSessionId = sessionId
    )

    private companion object {
        const val MAX_CACHED_CHAPTERS = 5
        const val CACHE_TTL_MILLIS = 5 * 60 * 1_000L
    }
}
