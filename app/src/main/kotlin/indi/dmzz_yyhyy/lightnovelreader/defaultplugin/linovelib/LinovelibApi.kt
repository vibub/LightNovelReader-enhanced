package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

import android.content.Context
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.runCatching as runCatchingResult
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore.LinovelibExplorePageProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibSearchProvider
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.error.mapAsWebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

private val CHAPTER_CONTENT_TIMEOUT = 60.seconds

@WebDataSource(
    name = "Linovelib",
    provider = "LightNovelReader from linovelib.com"
)
class LinovelibApi(
    userDataRepository: UserDataRepositoryApi,
    private val context: Context
) : WebBookDataSource {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val jsoup = LinovelibJsoup(accountStore)
    private val websiteDataSource = LinovelibWebsiteDataSource(jsoup)
    private val mutableOffline = MutableStateFlow(false)

    override val id = LinovelibConstants.SOURCE_ID
    override val cache: Cache = Cache(
        maxCountEachType = 50,
        timeout = 2 * 60 * 60 * 1000
    )
    // 实际 HTTP 请求由 LinovelibRateLimiter 串行；这里不再用少量槽位串行整个数据源方法。
    override val permits: Int = 64

    override val offLine: Boolean
        get() = mutableOffline.value

    override val isOffLineFlow: StateFlow<Boolean> = mutableOffline

    override val searchProvider: SearchProvider = LinovelibSearchProvider(
        jsoup,
        websiteDataSource,
        cache
    )
    override val explorePageProvider: ExplorePageProvider = LinovelibExplorePageProvider(jsoup, context::getString)

    override val imageHeader: Map<String, String>
        get() = jsoup.defaultHeaders(useCookie = false)

    override suspend fun isOffLine(): Boolean {
        val offline = runCatching {
            jsoup.getDocument(LinovelibConstants.BASE_URL, useCookie = false, retryTime = 0)
            false
        }.getOrElse {
            it !is LinovelibBlockedException
        }
        mutableOffline.value = offline
        return offline
    }

    override suspend fun getBookInformation(id: String) = runCatchingResult {
        websiteDataSource.getBookInformation(id)
    }.mapAsWebRequestError(
        title = context.getString(R.string.linovelib_book_info_error),
        message = context.getString(R.string.linovelib_book_info_error_detail, id)
    )

    override suspend fun getBookVolumes(id: String) = runCatchingResult {
        websiteDataSource.getBookVolumes(id)
    }.mapAsWebRequestError(
        title = context.getString(R.string.linovelib_catalog_error),
        message = context.getString(R.string.linovelib_catalog_error_detail, id)
    )

    override suspend fun getChapterContent(chapterId: String, bookId: String) = try {
        val content = withTimeoutOrNull(CHAPTER_CONTENT_TIMEOUT) {
            websiteDataSource.getChapterContent(chapterId, bookId)
        }
        if (content == null) {
            Err(
                WebRequestError(
                    title = context.getString(R.string.linovelib_chapter_timeout),
                    message = context.getString(R.string.linovelib_chapter_timeout_detail, chapterId)
                )
            )
        } else {
            Ok(content)
        }
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        Err(
            WebRequestError(
                title = context.getString(R.string.linovelib_chapter_error),
                message = context.getString(R.string.linovelib_chapter_error_detail, chapterId),
                throwable = throwable
            )
        )
    }
}
