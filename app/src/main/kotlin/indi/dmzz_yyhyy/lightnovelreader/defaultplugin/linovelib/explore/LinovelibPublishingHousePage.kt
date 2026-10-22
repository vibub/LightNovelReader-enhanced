package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreBookSummaryProvider
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ManagedExploreExpandedPageDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.explore.MultiChoiceExploreFilter
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime

internal sealed interface LinovelibFilterKey {
    data object Sort : LinovelibFilterKey

    data class Parameter(val index: Int) : LinovelibFilterKey
}

internal data class LinovelibFilterUrl(
    val sort: String,
    val parameters: List<String>
) {
    init {
        require(parameters.size == PARAMETER_COUNT)
    }

    val page: Int
        get() = parameters[PAGE_PARAMETER_INDEX].toIntOrNull() ?: 1

    fun withPage(page: Int): LinovelibFilterUrl = copy(
        parameters = parameters.toMutableList().apply {
            this[PAGE_PARAMETER_INDEX] = page.coerceAtLeast(1).toString()
        }
    )

    fun withOption(
        key: LinovelibFilterKey,
        optionUrl: LinovelibFilterUrl
    ): LinovelibFilterUrl = withValue(
        key = key,
        value = optionUrl.valueFor(key),
        relatedRegion = optionUrl.parameters[REGION_PARAMETER_INDEX]
    )

    fun withValue(
        key: LinovelibFilterKey,
        value: String,
        relatedRegion: String? = null
    ): LinovelibFilterUrl = when (key) {
        LinovelibFilterKey.Sort -> copy(sort = value)
        is LinovelibFilterKey.Parameter -> copy(
            parameters = parameters.toMutableList().apply {
                if (key.index == REGION_PARAMETER_INDEX) {
                    this[PUBLISHING_HOUSE_PARAMETER_INDEX] = "0"
                    this[TYPE_PARAMETER_INDEX] = "0"
                } else if (
                    key.index == PUBLISHING_HOUSE_PARAMETER_INDEX ||
                    key.index == TYPE_PARAMETER_INDEX
                ) {
                    relatedRegion?.let { this[REGION_PARAMETER_INDEX] = it }
                }
                this[key.index] = value
                this[PAGE_PARAMETER_INDEX] = "1"
            }
        )
    }.withPage(1)

    fun toAbsoluteUrl(): String = buildString {
        append(LinovelibConstants.FILTER_BASE_URL)
        append("/wenku/")
        append(sort)
        append('_')
        append(parameters.joinToString("_"))
        append(".html")
    }

    fun valueFor(key: LinovelibFilterKey): String = when (key) {
        LinovelibFilterKey.Sort -> sort
        is LinovelibFilterKey.Parameter -> parameters[key.index]
    }

    companion object {
        const val PARAMETER_COUNT = 9
        const val REGION_PARAMETER_INDEX = 3
        const val PUBLISHING_HOUSE_PARAMETER_INDEX = 4
        const val TYPE_PARAMETER_INDEX = 5
        const val PAGE_PARAMETER_INDEX = 7

        val Default = LinovelibFilterUrl(
            sort = "lastupdate",
            parameters = listOf("0", "0", "0", "0", "0", "0", "0", "1", "0")
        )
        val Metadata = Default.withValue(
            LinovelibFilterKey.Parameter(REGION_PARAMETER_INDEX),
            "1"
        )

        fun parse(url: String): LinovelibFilterUrl? {
            val match = FILTER_PAGE_URL_REGEX.find(url) ?: return null
            val parameters = match.groups[2]
                ?.value
                ?.split('_')
                ?.takeIf { it.size == PARAMETER_COUNT && it.all(FILTER_PARAMETER_REGEX::matches) }
                ?: return null
            return LinovelibFilterUrl(
                sort = match.groups[1]?.value?.lowercase().orEmpty(),
                parameters = parameters
            )
        }
    }
}

internal data class LinovelibFilterOption(
    val title: String,
    val url: LinovelibFilterUrl
)

internal data class LinovelibFilterGroup(
    val title: String,
    val key: LinovelibFilterKey,
    val options: List<LinovelibFilterOption>,
    val maxSelections: Int = 1
)

internal data class LinovelibFilterBook(
    val id: String,
    val title: String,
    val coverUrl: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val publishingHouse: String,
    val wordCount: Int,
    val lastUpdated: LocalDateTime,
    val isComplete: Boolean
) {
    fun toBookInformation(): BookInformation = BookInformation(
        id = id,
        title = title,
        coverUri = coverUrl.toUri(),
        author = author,
        description = description,
        tags = tags,
        publishingHouse = publishingHouse,
        wordCount = WordCount(wordCount),
        lastUpdated = lastUpdated,
        isComplete = isComplete
    )
}

internal data class LinovelibFilterPage(
    val filters: List<LinovelibFilterGroup>,
    val books: List<LinovelibFilterBook>,
    val hasNextPage: Boolean
) {
    val bookIds: List<String>
        get() = books.map(LinovelibFilterBook::id)
}

internal object LinovelibFilterPageParser {
    fun parse(
        document: Document,
        pageUrl: String
    ): LinovelibFilterPage {
        check(!document.isSecurityVerificationPage()) {
            "Linovelib 返回了 Cloudflare 安全验证页面"
        }
        val currentUrl = LinovelibFilterUrl.parse(pageUrl)
            ?: error("无法解析 Linovelib 筛选页地址：$pageUrl")
        val filters = document.parseFilterGroups(currentUrl)
        val books = document.parseFilterBooks()
        check(books.isNotEmpty() || document.select(".book-ol, .book-img-text, .book-list, .search-result").any {
            it.children().isEmpty() || Regex("(?:暂无|暫無|没有|沒有|未找到).{0,20}(?:小说|小說|作品|书籍|書籍|结果|結果)")
                .containsMatchIn(it.text())
        }) { "Linovelib 筛选页没有可识别的书单，请重试" }
        return LinovelibFilterPage(
            filters = filters,
            books = books,
            hasNextPage = document.hasNextFilterPage(currentUrl)
        )
    }
}

internal class LinovelibFilterPageCache(
    private val loadDocument: suspend (String) -> Document,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val cacheDurationMillis: Long = DEFAULT_CACHE_DURATION_MILLIS
) {
    private data class CachedPage(
        val page: LinovelibFilterPage,
        val expiresAtMillis: Long
    )

    private val mutex = Mutex()
    private val invalidationLock = Any()
    private var cachedMetadata: CachedPage? = null
    private val cachedPages = mutableMapOf<String, CachedPage>()
    private val invalidatedResultKeys = mutableSetOf<LinovelibFilterUrl>()

    suspend fun getMetadata(): LinovelibFilterPage = mutex.withLock {
        val now = currentTimeMillis()
        cachedMetadata
            ?.takeIf { now < it.expiresAtMillis }
            ?.let { return@withLock it.page }

        val url = LinovelibFilterUrl.Metadata.toAbsoluteUrl()
        val page = loadPage(url)
        check(page.filters.isNotEmpty()) {
            "Linovelib 筛选页没有可识别的筛选条件"
        }
        val cachedPage = CachedPage(page, now + cacheDurationMillis)
        cachedMetadata = cachedPage
        cachedPages[url] = cachedPage
        page
    }

    suspend fun getPage(
        url: LinovelibFilterUrl,
        forceRefresh: Boolean = false
    ): LinovelibFilterPage = mutex.withLock {
        val resultKey = url.withPage(1)
        val wasInvalidated = synchronized(invalidationLock) {
            invalidatedResultKeys.remove(resultKey)
        }
        if (wasInvalidated) {
            cachedPages.keys.removeAll { cachedUrl ->
                LinovelibFilterUrl.parse(cachedUrl)?.withPage(1) == resultKey
            }
        }

        val absoluteUrl = url.toAbsoluteUrl()
        val now = currentTimeMillis()
        if (!forceRefresh && !wasInvalidated) {
            cachedPages[absoluteUrl]
                ?.takeIf { now < it.expiresAtMillis }
                ?.let { return@withLock it.page }
        }
        loadPage(absoluteUrl).also { page ->
            cachedPages[absoluteUrl] = CachedPage(page, now + cacheDurationMillis)
        }
    }

    fun invalidateResultCache(url: LinovelibFilterUrl) {
        synchronized(invalidationLock) {
            invalidatedResultKeys += url.withPage(1)
        }
    }

    private suspend fun loadPage(url: String): LinovelibFilterPage =
        LinovelibFilterPageParser.parse(loadDocument(url), url)

    companion object {
        internal const val DEFAULT_CACHE_DURATION_MILLIS = 10 * 60 * 1000L
    }
}

internal class LinovelibFilterPageDataSource(
    private val initialPublishingHouse: String,
    private val pageCache: LinovelibFilterPageCache,
    override val filterDocumentLoader: LinovelibFilterDocumentLoader? = null
) : ExploreExpandedPageDataSource, ManagedExploreExpandedPageDataSource, ExploreBookSummaryProvider,
    LinovelibFilterBrowserDataSource {
    override val title: String = "筛选"

    private val initializationMutex = Mutex()
    private val stateLock = Any()
    private val bookSummaries = mutableMapOf<String, LinovelibFilterBook>()
    private val loadMoreRequests = Channel<Unit>(Channel.CONFLATED)
    private val mutableFiltersFlow = MutableStateFlow<List<Filter<*>>>(emptyList())

    override val filtersFlow: StateFlow<List<Filter<*>>> = mutableFiltersFlow
    override val filters: List<Filter<*>>
        get() = mutableFiltersFlow.value

    private var initialized = false
    private var currentUrl = LinovelibFilterUrl.Default
    private var loadedPage = 0
    private var requestedPage = 1
    private var morePagesAvailable = true
    override val hasNextPage: Boolean
        get() = synchronized(stateLock) { morePagesAvailable }
    private var forceRefreshFirstPage = false

    override fun reset() {
        synchronized(stateLock) {
            initialized = false
            currentUrl = LinovelibFilterUrl.Default
            resetPagingLocked()
            forceRefreshFirstPage = false
            while (loadMoreRequests.tryReceive().isSuccess) {
                // 清除上次页面会话遗留的加载信号。
            }
        }
        filterDocumentLoader?.resetSession()
        mutableFiltersFlow.value = emptyList()
    }

    override fun getResultFlow(): Flow<SearchResult> = flow {
        try {
            ensureInitialized()
            val emittedBookIds = mutableSetOf<String>()
            var page = 1
            var endEmitted = false
            while (true) {
                val targetPage = synchronized(stateLock) { requestedPage }
                if (page > targetPage) {
                    if (!endEmitted) {
                        emit(SearchResult.End())
                        endEmitted = true
                    }
                    loadMoreRequests.receive()
                    continue
                }

                endEmitted = false
                val pageUrl = synchronized(stateLock) { currentUrl.withPage(page) }
                val forceRefresh = synchronized(stateLock) {
                    forceRefreshFirstPage && page == 1
                }
                val result = pageCache.getPage(pageUrl, forceRefresh)
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (forceRefresh && page == 1) forceRefreshFirstPage = false
                    loadedPage = page
                    morePagesAvailable = result.hasNextPage
                }

                if (page == 1 && result.books.isEmpty()) {
                    emit(SearchResult.Empty())
                    return@flow
                }
                result.books
                    .filter { emittedBookIds.add(it.id) }
                    .forEach { book ->
                        synchronized(stateLock) { bookSummaries[book.id] = book }
                        emit(SearchResult.MultipleBook(book.id))
                    }

                page++
                if (!result.hasNextPage) {
                    emit(SearchResult.End())
                    return@flow
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emit(SearchResult.Error(error))
        }
    }

    override fun getBookSummary(bookId: String): BookInformation? =
        synchronized(stateLock) { bookSummaries[bookId] }?.toBookInformation()

    override fun loadMore() {
        requestLoadMore()
    }

    override fun requestLoadMore(): Boolean = synchronized(stateLock) {
        if (!initialized || !hasNextPage || loadedPage == 0 || requestedPage > loadedPage) {
            return@synchronized false
        }
        requestedPage = loadedPage + 1
        loadMoreRequests.trySend(Unit)
        true
    }

    override fun invalidateCurrentResultCache() {
        val invalidatedUrl = synchronized(stateLock) {
            resetPagingLocked()
            forceRefreshFirstPage = true
            while (loadMoreRequests.tryReceive().isSuccess) {
                // 刷新后只保留新的第一页加载信号。
            }
            currentUrl
        }
        pageCache.invalidateResultCache(invalidatedUrl)
    }

    private suspend fun ensureInitialized() = initializationMutex.withLock {
        if (synchronized(stateLock) { initialized }) return@withLock

        val metadata = pageCache.getMetadata()
        currentCoroutineContext().ensureActive()
        val publishingHouseGroup = metadata.filters.firstOrNull {
            it.key == LinovelibFilterKey.Parameter(
                LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
            )
        } ?: error("Linovelib 筛选页没有出版社选项")
        val publishingHouseOption = publishingHouseGroup.options.firstOrNull { option ->
            option.title.normalizeFilterName() == initialPublishingHouse.normalizeFilterName()
        } ?: error("筛选页中未找到出版社：$initialPublishingHouse")
        val selectedUrl = LinovelibFilterUrl.Default
            .withOption(publishingHouseGroup.key, publishingHouseOption.url)
        val filtersByKey = mutableMapOf<LinovelibFilterKey, Filter<*>>()
        var updatingDependentFilters = false

        fun updateSingleChoiceFilter(
            key: LinovelibFilterKey,
            selectedValue: String
        ) {
            val group = metadata.filters.firstOrNull { it.key == key } ?: return
            val selectedTitle = group.options.firstOrNull { option ->
                option.url.valueFor(key) == selectedValue
            }?.title ?: return
            val filter = filtersByKey[key] as? SingleChoiceFilter ?: return
            if (filter.value == selectedTitle) return
            updatingDependentFilters = true
            try {
                filter.value = selectedTitle
            } finally {
                updatingDependentFilters = false
            }
        }

        val builtFilters = metadata.filters.map { group ->
            val defaultOption = group.options.firstOrNull { option ->
                option.url.valueFor(group.key) ==
                    LinovelibFilterUrl.Default.valueFor(group.key)
            } ?: group.options.first()
            val filter: Filter<*> = if (group.maxSelections > 1) {
                val selectedValues = selectedUrl.valueFor(group.key).split('-').toSet()
                val selectedTitles = group.options
                    .filter { it.url.valueFor(group.key) in selectedValues }
                    .mapTo(linkedSetOf(), LinovelibFilterOption::title)
                    .let { titles ->
                        titles.ifEmpty { linkedSetOf(defaultOption.title) }
                    }
                MultiChoiceExploreFilter(
                    title = group.title.local(),
                    dialogTitle = group.title.local(),
                    description = "选择${group.title}，最多${group.maxSelections}项".local(),
                    choices = group.options.map(LinovelibFilterOption::title),
                    defaultChoice = defaultOption.title,
                    maxSelections = group.maxSelections
                ).apply {
                    value = normalizeSelection(selectedTitles)
                    addOnChangeListener(Int.MAX_VALUE) { selected ->
                        if (updatingDependentFilters) return@addOnChangeListener
                        val normalized = normalizeSelection(selected)
                        val selectedValue = if (defaultChoice in normalized) {
                            "0"
                        } else {
                            group.options
                                .filter { it.title in normalized }
                                .joinToString("-") { it.url.valueFor(group.key) }
                        }
                        synchronized(stateLock) {
                            currentUrl = currentUrl.withValue(group.key, selectedValue)
                            resetPagingLocked()
                        }
                    }
                }
            } else {
                val selectedOption = group.options.firstOrNull { option ->
                    option.url.valueFor(group.key) == selectedUrl.valueFor(group.key)
                } ?: defaultOption
                SingleChoiceFilter(
                    title = group.title.local(),
                    dialogTitle = group.title.local(),
                    description = "选择${group.title}".local(),
                    choices = group.options.map(LinovelibFilterOption::title),
                    defaultChoice = defaultOption.title
                ).apply {
                    value = selectedOption.title
                    addOnChangeListener(Int.MAX_VALUE) { selectedTitle ->
                        if (updatingDependentFilters) return@addOnChangeListener
                        val option = group.options.firstOrNull { it.title == selectedTitle }
                            ?: return@addOnChangeListener
                        synchronized(stateLock) {
                            currentUrl = currentUrl.withOption(group.key, option.url)
                            resetPagingLocked()
                        }
                        when (group.key) {
                            LinovelibFilterKey.Parameter(
                                LinovelibFilterUrl.REGION_PARAMETER_INDEX
                            ) -> updateSingleChoiceFilter(
                                key = LinovelibFilterKey.Parameter(
                                    LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
                                ),
                                selectedValue = "0"
                            )
                            LinovelibFilterKey.Parameter(
                                LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX
                            ) -> updateSingleChoiceFilter(
                                key = LinovelibFilterKey.Parameter(
                                    LinovelibFilterUrl.REGION_PARAMETER_INDEX
                                ),
                                selectedValue = option.url.parameters[
                                    LinovelibFilterUrl.REGION_PARAMETER_INDEX
                                ]
                            )
                            else -> Unit
                        }
                    }
                }
            }
            filtersByKey[group.key] = filter
            filter
        }

        synchronized(stateLock) {
            currentUrl = selectedUrl.withPage(1)
            resetPagingLocked()
            initialized = true
        }
        mutableFiltersFlow.value = builtFilters
    }

    private fun resetPagingLocked() {
        bookSummaries.clear()
        loadedPage = 0
        requestedPage = 1
        morePagesAvailable = true
    }
}

private fun Document.parseFilterGroups(
    currentUrl: LinovelibFilterUrl
): List<LinovelibFilterGroup> {
    val groups = select("#filters .jsFilter")
        .mapNotNull { it.parseDataFilterGroup(currentUrl) }
        .distinctBy(LinovelibFilterGroup::key)
        .toMutableList()
    val publishingHouseGroup = parsePublishingHouseGroup(currentUrl) ?: return groups
    val regionIndex = groups.indexOfFirst {
        it.key == LinovelibFilterKey.Parameter(LinovelibFilterUrl.REGION_PARAMETER_INDEX)
    }
    groups.add((regionIndex + 1).coerceAtLeast(0), publishingHouseGroup)
    return groups
}

private fun Element.parseDataFilterGroup(
    currentUrl: LinovelibFilterUrl
): LinovelibFilterGroup? {
    val anchors = select(".jsTag[data-filter-type][data-filter-value]")
    val filterType = anchors.firstOrNull()
        ?.attr("data-filter-type")
        ?.lowercase()
        ?: return null
    val key = filterType.toFilterKey() ?: return null
    val options = anchors
        .asSequence()
        .filter { it.attr("data-filter-type").equals(filterType, ignoreCase = true) }
        .mapNotNull { anchor ->
            val title = anchor.text().cleanFilterText().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val value = anchor.attr("data-filter-value")
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            LinovelibFilterOption(
                title = title,
                url = currentUrl.withValue(
                    key = key,
                    value = value,
                    relatedRegion = anchor.attr("data-filter-rgroup").takeIf(String::isNotBlank)
                )
            )
        }
        .distinctBy { it.url.valueFor(key) }
        .toList()
    if (options.size < 2) return null

    val title = selectFirst(".sort-li-title")
        ?.ownText()
        ?.toFilterGroupTitle()
        ?.takeIf(String::isNotBlank)
        ?: filterType.defaultFilterGroupTitle()
    return LinovelibFilterGroup(
        title = title,
        key = key,
        options = options,
        maxSelections = if (filterType == "tagid") MAX_THEME_SELECTIONS else 1
    )
}

private fun Document.parsePublishingHouseGroup(
    currentUrl: LinovelibFilterUrl
): LinovelibFilterGroup? {
    val key = LinovelibFilterKey.Parameter(LinovelibFilterUrl.PUBLISHING_HOUSE_PARAMETER_INDEX)
    val publishingHouses = getAllElements()
        .asSequence()
        .flatMap { element -> element.childNodes().asSequence().filterIsInstance<Comment>() }
        .flatMap { comment ->
            Jsoup.parseBodyFragment(comment.data)
                .select(".sortbox .jsTag[data-filter-type=sortid][data-filter-value]")
                .asSequence()
        }
        .mapNotNull { anchor ->
            val title = anchor.text().cleanFilterText().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val value = anchor.attr("data-filter-value")
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val relatedRegion = anchor.attr("data-filter-rgroup")
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            LinovelibFilterOption(
                title = title,
                url = currentUrl.withValue(key, value, relatedRegion)
            )
        }
        .distinctBy { option ->
            option.url.parameters[LinovelibFilterUrl.REGION_PARAMETER_INDEX] to
                option.url.valueFor(key)
        }
        .toList()
    if (publishingHouses.isEmpty()) return null

    val allPublishingHouses = LinovelibFilterOption(
        title = "所有文库",
        url = currentUrl.withValue(key, "0", relatedRegion = "0")
    )
    return LinovelibFilterGroup(
        title = "出版社",
        key = key,
        options = listOf(allPublishingHouses) + publishingHouses
    )
}

private fun String.toFilterKey(): LinovelibFilterKey? = when (lowercase()) {
    "order" -> LinovelibFilterKey.Sort
    "tagid" -> LinovelibFilterKey.Parameter(0)
    "isfull" -> LinovelibFilterKey.Parameter(1)
    "anime" -> LinovelibFilterKey.Parameter(2)
    "rgroupid" -> LinovelibFilterKey.Parameter(3)
    "sortid" -> LinovelibFilterKey.Parameter(4)
    "typeid" -> LinovelibFilterKey.Parameter(5)
    "words" -> LinovelibFilterKey.Parameter(6)
    "update" -> LinovelibFilterKey.Parameter(8)
    else -> null
}

private fun String.defaultFilterGroupTitle(): String = when (lowercase()) {
    "order" -> "排序方式"
    "tagid" -> "作品主题"
    "isfull" -> "写作状态"
    "anime" -> "是否动画"
    "rgroupid" -> "文库地区"
    "sortid" -> "出版社"
    "typeid" -> "作品类型"
    "words" -> "作品字数"
    "update" -> "更新时间"
    else -> "筛选"
}

private fun Document.parseFilterBooks(): List<LinovelibFilterBook> {
    val containers = FILTER_BOOK_CONTAINER_SELECTORS
        .asSequence()
        .map(::select)
        .firstOrNull { selected ->
            selected.any { container ->
                container.select("a[href]").any { it.filterBookId() != null }
            }
        }
        .orEmpty()
    return containers.mapNotNull { container ->
        val link = container.select("a[href]").firstOrNull { it.filterBookId() != null }
            ?: return@mapNotNull null
        val id = link.filterBookId() ?: return@mapNotNull null
        val image = container.selectFirst("img")
        val titleElement = container.selectFirst(".book-title, .bookname, .book-name, h3, h4")
        val title = titleElement?.selectFirst("a[title]")?.attr("title")
            ?.takeIf(String::isNotBlank)
            ?: titleElement?.text()?.takeIf(String::isNotBlank)
            ?: link.attr("title").takeIf(String::isNotBlank)
            ?: image?.attr("alt")?.takeIf(String::isNotBlank)
            ?: link.ownText().takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val metadata = container.selectFirst(
            ".book-meta, .bookother, .bookinfo .author, .bookinfo .info, " +
                ".book-mid-info .author, .book-info .author, p.author"
        )?.text().orEmpty()
        val metadataParts = metadata.split('|', '｜').map(String::trim)
            .takeIf { it.size >= 2 }.orEmpty()
        val author = container.selectFirst(
            ".book-author, .author .name, .au-name, a[href*=/author/], " +
                ".book-mid-info .author a:first-of-type, .book-info .author a:first-of-type"
        )
            ?.text()
            ?: metadataParts.firstOrNull()
            ?: container.selectFirst(".author")?.text().orEmpty()
        val updateText = container.selectFirst("time[datetime]")?.attr("datetime")
            ?: container.selectFirst(".uptime, .update-time, .lastupdate, .book-update, .update")?.text()
            ?: metadata
        val wordText = container.selectFirst(".tag-small.blue, .words, .wordcount, .book-word-count")
            ?.text()
            ?: Regex("[\\d,.]+\\s*(?:[万萬wW千kK]字?|字)").find(metadata)?.value.orEmpty()
        LinovelibFilterBook(
            id = id,
            title = title.cleanFilterText(),
            coverUrl = image?.realExploreImageUrl()?.takeIf(String::isNotBlank)
                ?: inferLinovelibCoverUrl(id),
            author = author.substringBefore('|').substringBefore('｜')
                .cleanFilterText().removePrefix("作者：").removePrefix("作者:").trim(),
            description = container.selectFirst(
                ".book-desc, .bookintro, .book-intro, .intro, .desc"
            )?.text()?.cleanFilterText().orEmpty(),
            tags = container.select(".tag-small.yellow, .book-tags a, .tags a, .book-tag")
                .flatMap { it.text().cleanFilterText().split(' ') }
                .filter(String::isNotBlank)
                .distinct(),
            publishingHouse = container.selectFirst(
                ".book-publisher, .publisher, .book-category, .cat, .author a[href*=/wenku/]"
            )?.text()?.cleanFilterText() ?: metadataParts.getOrNull(1).orEmpty(),
            wordCount = wordText.parseFilterWordCount(),
            lastUpdated = updateText.parseFilterUpdateTime(),
            isComplete = Regex("(?<!未)(?:完结|完結|完本|全本)").containsMatchIn(
                container.select(".tag-small.red, .book-status, .status, .author span").text() + " " + metadata
            )
        )
    }.distinctBy(LinovelibFilterBook::id)
}

private fun String.parseFilterWordCount(): Int {
    val match = Regex("([\\d,]+(?:\\.\\d+)?)\\s*([万萬wW千kK]?)").find(this) ?: return 0
    val count = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0
    val multiplier = when (match.groupValues[2].lowercase()) {
        "万", "萬", "w" -> 10_000
        "千", "k" -> 1_000
        else -> 1
    }
    return (count * multiplier).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
}

private fun String.parseFilterUpdateTime(): LocalDateTime {
    val match = Regex(
        "(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[T\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?"
    ).find(this) ?: return LocalDateTime.MIN
    return runCatching {
        LocalDateTime.of(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            match.groupValues[4].toIntOrNull() ?: 0,
            match.groupValues[5].toIntOrNull() ?: 0,
            match.groupValues[6].toIntOrNull() ?: 0
        )
    }.getOrDefault(LocalDateTime.MIN)
}

private fun Document.hasNextFilterPage(currentUrl: LinovelibFilterUrl): Boolean {
    val pageLinks = select(".pagelink a[href], #pagelink a[href]")
        .mapNotNull { LinovelibFilterUrl.parse(it.attr("href")) }
    if (pageLinks.any { it.page > currentUrl.page }) return true
    val pageStats = selectFirst("#pagestats, .pagestats")
        ?.text()
        ?.let(PAGE_STATS_REGEX::find)
    val totalPage = pageStats?.groups?.get(2)?.value?.toIntOrNull()
    return totalPage != null && currentUrl.page < totalPage
}

internal fun Document.isSecurityVerificationPage(): Boolean {
    val normalizedTitle = title().lowercase()
    val normalizedText = text().lowercase()
    return "just a moment" in normalizedTitle ||
        "performing security verification" in normalizedText ||
        "security service to protect against malicious bots" in normalizedText ||
        select("#challenge-running, #cf-challenge-running, #challenge-stage, .cf-turnstile, " +
            "script[src*=/cdn-cgi/challenge-platform/], iframe[src*=challenges.cloudflare.com], " +
            "form[action*=/cdn-cgi/challenge-platform/], input[name*=captcha], input[id*=captcha], img[src*=captcha]")
            .isNotEmpty()
}

private fun Element.filterBookId(): String? = FILTER_BOOK_PAGE_REGEX
    .find(attr("href"))
    ?.groups
    ?.get(1)
    ?.value

private fun String.cleanFilterText(): String = replace(' ', ' ')
    .replace(Regex("\\s+"), " ")
    .trim()

private fun String.toFilterGroupTitle(): String = cleanFilterText()
    .removeSuffix("：")
    .removeSuffix(":")
    .trim()

private fun String.normalizeFilterName(): String = cleanFilterText()
    .replace("·", "")
    .replace("・", "")
    .replace(" ", "")
    .lowercase()

private const val MAX_THEME_SELECTIONS = 4
private val FILTER_PARAMETER_REGEX = Regex("\\d+(?:-\\d+)*")
private val FILTER_PAGE_URL_REGEX = Regex(
    "^(?:https?://[^/]+)?/wenku/([a-z]+)_((?:[\\d-]+_){8}[\\d-]+)\\.html(?:[?#].*)?$",
    RegexOption.IGNORE_CASE
)
private val FILTER_BOOK_PAGE_REGEX = Regex("/novel/(\\d+)\\.html(?:[?#].*)?$")
private val PAGE_STATS_REGEX = Regex("(\\d+)\\s*/\\s*(\\d+)")
private val FILTER_BOOK_CONTAINER_SELECTORS = listOf(
    ".book-ol .book-li",
    ".book-img-text li",
    "div.mind-book",
    "div.bookbox",
    ".book-list .book-item",
    ".search-result .book-item"
)
