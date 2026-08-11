package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object LinovelibPublishingHousePageParser {
    fun parseBookIds(document: Document): List<String> {
        val scopedAnchors = PUBLISHING_HOUSE_BOOK_CONTAINER_SELECTORS
            .asSequence()
            .map(document::select)
            .filter { it.isNotEmpty() }
            .map { containers -> containers.flatMap { it.select("a[href]") } }
            .firstOrNull { anchors -> anchors.any { it.publishingHouseBookId() != null } }
            ?: document.select("#content a[href], main a[href]")
        return scopedAnchors
            .mapNotNull(Element::publishingHouseBookId)
            .distinct()
    }
}

internal class LinovelibPublishingHousePageDataSource(
    override val title: String,
    private val pageUrl: String,
    private val loadDocument: suspend (String) -> Document
) : ExploreExpandedPageDataSource {
    override val filters: List<Filter<*>> = emptyList()

    override fun getResultFlow(): Flow<SearchResult> = flow {
        val bookIds = try {
            LinovelibPublishingHousePageParser.parseBookIds(loadDocument(pageUrl))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emit(SearchResult.Error(error))
            return@flow
        }
        if (bookIds.isEmpty()) {
            emit(SearchResult.Empty())
            return@flow
        }
        bookIds.forEach { emit(SearchResult.MultipleBook(it)) }
        emit(SearchResult.End())
    }

    override fun loadMore() = Unit
}

private fun Element.publishingHouseBookId(): String? = PUBLISHING_HOUSE_BOOK_PAGE_REGEX
    .find(attr("href"))
    ?.groups
    ?.get(1)
    ?.value

private val PUBLISHING_HOUSE_BOOK_CONTAINER_SELECTORS = listOf(
    ".book-list",
    ".search-result",
    ".bookbox",
    ".book-item",
    ".book-layout",
    "#content .wrap",
    "main .wrap"
)
private val PUBLISHING_HOUSE_BOOK_PAGE_REGEX = Regex("/novel/(\\d+)\\.html(?:[?#].*)?$")
