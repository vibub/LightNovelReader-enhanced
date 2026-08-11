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
    private val loadPublishingHouseDocument: suspend (String) -> Document
) : AbstractDefaultExplorePageProvider() {
    constructor(
        jsoup: LinovelibJsoup,
        websiteDataSource: LinovelibWebsiteDataSource
    ) : this(
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
        ),
        loadPublishingHouseDocument = { url ->
            jsoup.getDocument(
                url = url,
                referer = LinovelibConstants.BASE_URL,
                retryTime = 0,
                userAgentMode = LinovelibJsoup.UserAgentMode.Desktop
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
                exploreExpandedPageDataSource = LinovelibPublishingHousePageDataSource(
                    title = publishingHouse.title,
                    pageUrl = publishingHouse.pageUrl,
                    loadDocument = loadPublishingHouseDocument
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
        val rows = when (page) {
            LinovelibExplorePage.Home,
            LinovelibExplorePage.Ranking -> snapshot.sections(page).mapNotNull { (section, books) ->
                books.toExploreDisplayBooks()
                    .takeIf(List<ExploreDisplayBook>::isNotEmpty)
                    ?.let { ExploreBooksRow(section.title, it) }
            }
            LinovelibExplorePage.PublishingHouse -> {
                onPublishingHousesLoaded(snapshot.publishingHouses)
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
            }
        }
        emit(rows)
    }.flowOn(Dispatchers.IO)

    override fun invalidateCache() {
        homepageLoader.invalidate()
    }
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
