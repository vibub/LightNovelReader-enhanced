package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import android.net.Uri
import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.data.explore.RefreshableExploreTapPageDataSource
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
    private val homepageLoader = LinovelibExploreHomepageLoader(
        loadDesktopSnapshot = {
            LinovelibExploreHomepageParser.parse(
                jsoup.getDocument(
                    url = LinovelibConstants.BASE_URL,
                    referer = LinovelibConstants.BASE_URL,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Desktop
                )
            )
        },
        loadMobileRecommendations = {
            websiteDataSource.parseExploreBooks(
                jsoup.getDocument(
                    url = LinovelibConstants.MOBILE_BASE_URL,
                    referer = LinovelibConstants.MOBILE_BASE_URL,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
                )
            )
        }
    )

    init {
        LinovelibExploreSection.entries.forEach { section ->
            registerTapPage(
                section.pageId,
                LinovelibExploreTapPage(section, homepageLoader)
            )
        }
    }
}

private class LinovelibExploreTapPage(
    private val section: LinovelibExploreSection,
    private val homepageLoader: LinovelibExploreHomepageLoader
) : ExploreTapPageDataSource, RefreshableExploreTapPageDataSource {
    override val title: String = section.title

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val books = homepageLoader.getBooks(section).map { book ->
            val coverUrl = book.coverUrl.ifBlank { inferLinovelibCoverUrl(book.id) }
            ExploreDisplayBook(
                id = book.id,
                title = book.title,
                author = book.author,
                coverUri = LinovelibJsoup.normalizeCoverUrl(coverUrl)
                    .takeIf(String::isNotBlank)
                    ?.toUri()
                    ?: Uri.EMPTY
            )
        }
        emit(
            if (books.isEmpty()) emptyList()
            else listOf(ExploreBooksRow(LinovelibConstants.SOURCE_NAME, books))
        )
    }.flowOn(Dispatchers.IO)

    override fun invalidateCache() {
        homepageLoader.invalidate()
    }
}
