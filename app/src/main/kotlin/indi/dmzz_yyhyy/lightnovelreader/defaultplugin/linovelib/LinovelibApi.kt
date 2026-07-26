package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

import com.github.michaelbull.result.runCatching as runCatchingResult
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore.LinovelibExplorePageProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibBlockedException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibSearchProvider
import io.nightfish.lightnovelreader.api.error.mapAsWebRequestError
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import io.nightfish.lightnovelreader.api.util.Cache
import io.nightfish.lightnovelreader.api.web.WebBookDataSource
import io.nightfish.lightnovelreader.api.web.WebDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.search.SearchProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@WebDataSource(
    name = "Linovelib",
    provider = "LightNovelReader from linovelib.com"
)
class LinovelibApi(
    userDataRepository: UserDataRepositoryApi
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
    override val permits: Int = 1

    override val offLine: Boolean
        get() = mutableOffline.value

    override val isOffLineFlow: StateFlow<Boolean> = mutableOffline

    override val searchProvider: SearchProvider = LinovelibSearchProvider(
        jsoup,
        websiteDataSource,
        cache
    )
    override val explorePageProvider: ExplorePageProvider = LinovelibExplorePageProvider(jsoup, websiteDataSource)

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
        title = "Linovelib 书籍信息请求失败",
        message = "无法获取书籍 $id 的详情"
    )

    override suspend fun getBookVolumes(id: String) = runCatchingResult {
        websiteDataSource.getBookVolumes(id)
    }.mapAsWebRequestError(
        title = "Linovelib 目录请求失败",
        message = "无法获取书籍 $id 的目录"
    )

    override suspend fun getChapterContent(chapterId: String, bookId: String) = runCatchingResult {
        websiteDataSource.getChapterContent(chapterId, bookId)
    }.mapAsWebRequestError(
        title = "Linovelib 章节请求失败",
        message = "无法获取章节 $chapterId"
    )
}
