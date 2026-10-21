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
import org.jsoup.nodes.Document

class LinovelibExplorePageProvider internal constructor(
    private val homepageLoader: LinovelibExploreHomepageLoader,
    loadFilterDocument: suspend (String) -> Document
) : AbstractDefaultExplorePageProvider() {
    private val filterPageCache = LinovelibFilterPageCache(loadFilterDocument)
    constructor(jsoup: LinovelibJsoup) : this(
        homepageLoader = LinovelibExploreHomepageLoader(
            loadDesktopSnapshot = {
                LinovelibExploreHomepageParser.parse(
                    jsoup.getDocument(
                        url = LinovelibConstants.BASE_URL,
                        referer = LinovelibConstants.BASE_URL,
                        retryTime = 0,
                        userAgentMode = LinovelibJsoup.UserAgentMode.Desktop
                    )
                )
            }
        ),
        loadFilterDocument = { url ->
            // 此加载器仅由 ExpandedPage 的进入、筛选、刷新、翻页及手动重试触发。
            jsoup.getDocument(
                url = url,
                referer = LinovelibConstants.BASE_URL,
                retryTime = 0,
                userAgentMode = LinovelibJsoup.UserAgentMode.Desktop,
                requestIntent = LinovelibJsoup.RequestIntent.UserInitiated
            )
        }
    )

    init {
        LinovelibExplorePage.entries.forEach { page ->
            registerTapPage(
                page.pageId,
                LinovelibExploreTapPage(
                    page = page,
                    homepageLoader = homepageLoader,
                    onPublishingHousesLoaded = ::registerPublishingHousePages
                )
            )
        }
    }

    internal fun registerPublishingHousePages(publishingHouses: List<LinovelibExplorePublishingHouse>) {
        exploreExpandedPageDataSourceMap.clear()
        publishingHouses.forEach { publishingHouse ->
            registerExpandedPageDataSource(
                id = publishingHouse.expandedPageDataSourceId,
                exploreExpandedPageDataSource = LinovelibFilterPageDataSource(
                    initialPublishingHouse = publishingHouse.title,
                    pageCache = filterPageCache
                )
            )
        }
    }
}

private class LinovelibExploreTapPage(
    private val page: LinovelibExplorePage,
    private val homepageLoader: LinovelibExploreHomepageLoader,
    private val onPublishingHousesLoaded: (List<LinovelibExplorePublishingHouse>) -> Unit
) : ExploreTapPageDataSource, RefreshableExploreTapPageDataSource {
    override val title: String = page.title

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val snapshot = homepageLoader.getSnapshot()
        when (page) {
            LinovelibExplorePage.Home -> emit(snapshot.sections(page).toExploreRows())
            LinovelibExplorePage.Ranking -> {
                emit(
                    snapshot.sections(page).map { (section, books) ->
                        section to books.map { it.copy(author = "") }
                    }.toExploreRows()
                )
            }
            LinovelibExplorePage.PublishingHouse -> {
                onPublishingHousesLoaded(snapshot.publishingHouses)
                emit(
                    snapshot.publishingHouses.mapNotNull { publishingHouse ->
                        publishingHouse.books.toExploreDisplayBooks()
                            .takeIf(List<ExploreDisplayBook>::isNotEmpty)
                            ?.let { books ->
                                ExploreBooksRow(
                                    title = publishingHouse.title,
                                    bookList = books,
                                    expandable = true,
                                    expandedPageDataSourceId = publishingHouse.expandedPageDataSourceId
                                )
                            }
                    }
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun invalidateCache() {
        homepageLoader.invalidate()
    }
}

private fun List<Pair<LinovelibExploreSection, List<LinovelibWebsiteDataSource.LinovelibExploreBook>>>.toExploreRows(): List<ExploreBooksRow> =
    mapNotNull { (section, books) ->
        books.toExploreDisplayBooks()
            .takeIf(List<ExploreDisplayBook>::isNotEmpty)
            ?.let { ExploreBooksRow(section.title, it) }
    }

private fun List<LinovelibWebsiteDataSource.LinovelibExploreBook>.toExploreDisplayBooks(): List<ExploreDisplayBook> =
    map { book ->
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
