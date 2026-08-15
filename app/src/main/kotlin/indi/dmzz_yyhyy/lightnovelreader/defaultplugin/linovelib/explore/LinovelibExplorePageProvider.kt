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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document

class LinovelibExplorePageProvider internal constructor(
    private val homepageLoader: LinovelibExploreHomepageLoader,
    loadFilterDocument: suspend (String) -> Document,
    private val authorResolver: LinovelibExploreAuthorResolver = LinovelibExploreAuthorResolver { "" }
) : AbstractDefaultExplorePageProvider() {
    private val filterPageCache = LinovelibFilterPageCache(loadFilterDocument)
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
        loadFilterDocument = { url ->
            jsoup.getDocument(
                url = url,
                referer = LinovelibConstants.FILTER_BASE_URL,
                retryTime = 0,
                userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
            )
        },
        authorResolver = LinovelibExploreAuthorResolver { bookId ->
            websiteDataSource.getBookInformation(bookId).author
        }
    )

    init {
        LinovelibExplorePage.entries.forEach { page ->
            registerTapPage(
                page.pageId,
                LinovelibExploreTapPage(
                    page = page,
                    homepageLoader = homepageLoader,
                    authorResolver = authorResolver,
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
    private val authorResolver: LinovelibExploreAuthorResolver,
    private val onPublishingHousesLoaded: (List<LinovelibExplorePublishingHouse>) -> Unit
) : ExploreTapPageDataSource, RefreshableExploreTapPageDataSource {
    override val title: String = page.title

    override fun getRowsFlow(): Flow<List<ExploreBooksRow>> = flow {
        val snapshot = homepageLoader.getSnapshot()
        when (page) {
            LinovelibExplorePage.Home -> emit(snapshot.sections(page).toExploreRows())
            LinovelibExplorePage.Ranking -> {
                val rankingSections = snapshot.sections(page)
                    .map { (section, books) -> section to books.toMutableList() }
                emit(rankingSections.toExploreRows())
                rankingSections.forEach { (_, books) ->
                    books.forEachIndexed { index, book ->
                        if (book.author.isNotBlank()) return@forEachIndexed
                        val author = authorResolver.resolve(book.id)
                        if (author.isBlank()) return@forEachIndexed
                        books[index] = book.copy(author = author)
                        emit(rankingSections.toExploreRows())
                    }
                }
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

internal class LinovelibExploreAuthorResolver(
    private val loadAuthor: suspend (String) -> String
) {
    private val mutex = Mutex()
    private val authorCache = mutableMapOf<String, String>()

    suspend fun resolve(bookId: String): String = mutex.withLock {
        authorCache[bookId]?.let { return@withLock it }
        val author = try {
            loadAuthor(bookId).trim()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ""
        }
        authorCache[bookId] = author
        author
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
