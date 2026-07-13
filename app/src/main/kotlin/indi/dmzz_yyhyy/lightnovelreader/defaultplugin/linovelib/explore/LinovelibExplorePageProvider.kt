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
import kotlin.coroutines.cancellation.CancellationException

internal enum class LinovelibExploreSource {
    Desktop,
    Mobile
}

internal suspend fun loadLinovelibExploreBooks(
    limit: Int = 12,
    onError: (Throwable) -> Unit = { it.printStackTrace() },
    loadBooks: suspend (LinovelibExploreSource) -> List<LinovelibWebsiteDataSource.LinovelibExploreBook>
): List<LinovelibWebsiteDataSource.LinovelibExploreBook> {
    if (limit <= 0) return emptyList()

    suspend fun load(source: LinovelibExploreSource): List<LinovelibWebsiteDataSource.LinovelibExploreBook> = try {
        loadBooks(source)
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onError(error)
        emptyList()
    }

    val desktopBooks = load(LinovelibExploreSource.Desktop)
    if (desktopBooks.size >= limit) return desktopBooks.take(limit)

    val mobileBooks = load(LinovelibExploreSource.Mobile)
    return (desktopBooks + mobileBooks)
        .distinctBy { it.id }
        .take(limit)
}

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
        val books = loadLinovelibExploreBooks { source ->
            val document = when (source) {
                LinovelibExploreSource.Desktop -> jsoup.getDocument(
                    url = LinovelibConstants.BASE_URL,
                    referer = LinovelibConstants.BASE_URL,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Desktop,
                    coolDownOnCloudflare = false
                )
                LinovelibExploreSource.Mobile -> jsoup.getDocument(
                    url = LinovelibConstants.MOBILE_BASE_URL,
                    referer = LinovelibConstants.MOBILE_BASE_URL,
                    retryTime = 0,
                    userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
                )
            }
            websiteDataSource.parseExploreBooks(document)
        }.map {
            ExploreDisplayBook(
                id = it.id,
                title = it.title,
                author = it.author,
                coverUri = LinovelibJsoup.normalizeCoverUrl(it.coverUrl).takeIf { url -> url.isNotBlank() }?.toUri()
                    ?: android.net.Uri.EMPTY
            )
        }
        emit(
            if (books.isEmpty()) emptyList()
            else listOf(ExploreBooksRow("Linovelib", books))
        )
    }.flowOn(Dispatchers.IO)
}
