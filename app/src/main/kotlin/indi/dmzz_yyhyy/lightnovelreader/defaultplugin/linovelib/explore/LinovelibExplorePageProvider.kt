package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.explore.ExploreBooksRow
import io.nightfish.lightnovelreader.api.explore.ExploreDisplayBook
import io.nightfish.lightnovelreader.api.web.explore.AbstractDefaultExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.ExploreTapPageDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class LinovelibExplorePageProvider(
    jsoup: LinovelibJsoup,
    websiteDataSource: LinovelibWebsiteDataSource
) : AbstractDefaultExplorePageProvider() {
    init {
        registerTapPage("home", LinovelibHomeExploreTapPage(jsoup, websiteDataSource))
    }
}

private class LinovelibHomeExploreTapPage(
    private val jsoup: LinovelibJsoup,
    private val websiteDataSource: LinovelibWebsiteDataSource
) : ExploreTapPageDataSource {
    override val title: String = "首页"

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val books = runCatching {
            val document = jsoup.getDocument(
                url = LinovelibConstants.BASE_URL,
                retryTime = 0,
                userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
            )
            websiteDataSource.parseExploreBooks(document)
                .take(12)
                .map {
                    ExploreDisplayBook(
                        id = it.id,
                        title = it.title,
                        author = it.author,
                        coverUri = LinovelibJsoup.normalizeUrl(it.coverUrl).takeIf { url -> url.isNotBlank() }?.toUri()
                            ?: android.net.Uri.EMPTY
                    )
                }
        }.getOrElse {
            it.printStackTrace()
            emptyList()
        }
        emit(
            if (books.isEmpty()) emptyList()
            else listOf(ExploreBooksRow("Linovelib", books))
        )
    }.flowOn(Dispatchers.IO)
}
