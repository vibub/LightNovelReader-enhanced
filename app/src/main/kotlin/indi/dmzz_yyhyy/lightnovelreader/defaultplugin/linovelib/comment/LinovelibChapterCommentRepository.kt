package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment

import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

internal data class LinovelibCommentRequest(
    val url: String,
    val referer: String,
    val formData: Map<String, String>,
    val useCookie: Boolean,
    val pageIndex: Int
)

interface LinovelibChapterCommentDataSource {
    fun getCookieFlow(): Flow<String>
    fun hasCookie(): Boolean

    suspend fun loadComments(
        bookId: String,
        chapterId: String,
        refererChapterPageId: String,
        query: LinovelibCommentQuery,
        pageIndex: Int = 1
    ): LinovelibCommentPage
}

@Singleton
class LinovelibChapterCommentRepository @Inject constructor(
    userDataRepository: UserDataRepository
) : LinovelibChapterCommentDataSource {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val jsoup = LinovelibJsoup(accountStore)

    override fun getCookieFlow(): Flow<String> = accountStore.getCookieFlow()

    override fun hasCookie(): Boolean = accountStore.hasCookie()

    override suspend fun loadComments(
        bookId: String,
        chapterId: String,
        refererChapterPageId: String,
        query: LinovelibCommentQuery,
        pageIndex: Int
    ): LinovelibCommentPage {
        val request = buildLinovelibCommentRequest(
            bookId = bookId,
            chapterId = chapterId,
            refererChapterPageId = refererChapterPageId,
            query = query,
            pageIndex = pageIndex,
            hasCookie = accountStore.hasStoredCookie()
        )
        return try {
            val raw = jsoup.postFormRaw(
                url = request.url,
                formData = request.formData,
                referer = request.referer,
                useCookie = request.useCookie,
                retryTime = 1
            )
            LinovelibChapterCommentParser.parsePage(raw, request.pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinovelibCommentProtocolException) {
            if (isLinovelibCommentLoginRequiredMessage(e.message.orEmpty())) {
                throw LinovelibCommentLoginRequiredException(e.message.orEmpty(), e)
            }
            throw e
        }
    }
}

internal fun buildLinovelibCommentRequest(
    bookId: String,
    chapterId: String,
    refererChapterPageId: String,
    query: LinovelibCommentQuery,
    pageIndex: Int,
    hasCookie: Boolean
): LinovelibCommentRequest {
    val normalizedBookId = LinovelibConstants.run { bookId.normalizeBookId() }
    val normalizedChapterId = LinovelibConstants.run { chapterId.normalizeChapterId() }
        .substringBefore('_')
    val normalizedRefererPageId = LinovelibConstants.run {
        refererChapterPageId.normalizeChapterId()
    }.ifBlank { normalizedChapterId }
    if (normalizedBookId.isBlank() || normalizedChapterId.isBlank()) {
        throw LinovelibCommentProtocolException("Linovelib 评论请求缺少书籍或章节 ID")
    }
    if (query == LinovelibCommentQuery.All && !hasCookie) {
        throw LinovelibCommentLoginRequiredException("登录后才能查看全部评论")
    }

    val normalizedPageIndex = pageIndex.coerceAtLeast(1)
    val formData = linkedMapOf(
        "cmtid" to normalizedChapterId,
        "catid" to normalizedBookId,
        "pageSize" to if (query == LinovelibCommentQuery.Hot) HOT_PAGE_SIZE else ALL_PAGE_SIZE
    ).apply {
        if (query == LinovelibCommentQuery.All) {
            put("pageIndex", normalizedPageIndex.toString())
        }
        put("query", if (query == LinovelibCommentQuery.Hot) "hot" else "all")
    }
    return LinovelibCommentRequest(
        url = COMMENT_LIST_URL,
        referer = LinovelibConstants.chapterUrl(normalizedBookId, normalizedRefererPageId),
        formData = formData,
        useCookie = hasCookie,
        pageIndex = normalizedPageIndex
    )
}

internal fun isLinovelibCommentLoginRequiredMessage(message: String): Boolean {
    val normalized = message.trim().lowercase()
    return LOGIN_REQUIRED_MARKERS.any { it in normalized }
}

class LinovelibCommentLoginRequiredException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

private const val HOT_PAGE_SIZE = "5"
private const val ALL_PAGE_SIZE = "20"
private const val COMMENT_LIST_URL =
    "${LinovelibConstants.BASE_URL}/comment/php/api.php?action=get_list"
private val LOGIN_REQUIRED_MARKERS = listOf(
    "请先登录",
    "請先登入",
    "登录后",
    "登入後",
    "重新登录",
    "重新登入",
    "cookie 可能已失效",
    "cookie 已失效"
)
