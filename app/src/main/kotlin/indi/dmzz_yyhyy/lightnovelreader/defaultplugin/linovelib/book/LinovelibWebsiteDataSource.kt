package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import android.net.Uri
import androidx.core.net.toUri
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.MutableBookInformation
import io.nightfish.lightnovelreader.api.book.MutableChapterContent
import io.nightfish.lightnovelreader.api.book.Volume
import io.nightfish.lightnovelreader.api.book.WordCount
import io.nightfish.lightnovelreader.api.content.builder.ContentBuilder
import io.nightfish.lightnovelreader.api.content.builder.image
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

class LinovelibWebsiteDataSource(
    private val jsoup: LinovelibJsoup
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-M-d")

    suspend fun getBookInformation(id: String): BookInformation = runCatching {
        val bookId = id.normalizeBookId()
        if (bookId.isBlank()) return@runCatching BookInformation.empty(id)
        val document = jsoup.getDocument(LinovelibConstants.detailUrl(bookId))
        if (document.text().contains("作品已下架") || document.title().contains("404")) {
            return@runCatching BookInformation.empty(bookId)
        }
        val title = document.metaContent("name")
            ?: document.metaContent("og:novel:book_name")
            ?: document.metaContent("og:title")?.substringBefore("_")
            ?: document.firstText("h1", ".book-title", ".book-name", ".book-info h2")
            ?: return@runCatching BookInformation.empty(bookId)
        val author = document.metaContent("author")
            ?: document.metaContent("og:novel:author")
            ?: document.labelValue("作者")
            ?: document.firstText(".book-info a[href*=author]", ".book-author", ".author")
            ?: ""
        val cover = document.metaContent("pic")
            ?: document.metaContent("og:image")
            ?: document.firstCoverImageUrl()
            ?: ""
        val description = document.metaContent("description")
            ?: document.firstText("#bookIntro", ".book-intro", ".book-desc", ".intro", ".book-dec")
            ?: ""
        val tags = (document.metaContent("tags") ?: document.metaContent("keywords") ?: document.labelValue("标签") ?: "")
            .split(" ", ",", "，", "/", "、")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != title }
            .distinct()
        val statusText = document.metaContent("og:novel:status")
            ?: document.labelValue("状态")
            ?: document.text()
        val updateText = document.metaContent("og:novel:update_time")
            ?: document.metaContent("lastupdate")
            ?: document.labelValue("更新")
            ?: document.labelValue("最后更新")
            ?: ""
        val wordText = document.labelValue("字数") ?: document.labelValue("全文长度") ?: ""
        MutableBookInformation(
            id = bookId,
            title = title.cleanText(),
            subtitle = "",
            coverUrl = LinovelibJsoup.normalizeUrl(cover).takeIf { it.isNotBlank() }?.toUri() ?: Uri.EMPTY,
            author = author.cleanText(),
            description = description.cleanDescription(),
            tags = tags,
            publishingHouse = document.labelValue("文库")?.cleanText() ?: "",
            wordCount = WordCount(wordText.parseWordCount()),
            lastUpdated = updateText.parseDateTime(),
            isComplete = statusText.contains("完结") || statusText.contains("已完成")
        )
    }.getOrElse {
        if (it is CancellationException) throw it
        it.printStackTrace()
        BookInformation.empty(id)
    }

    suspend fun getBookVolumes(id: String): BookVolumes = runCatching {
        val bookId = id.normalizeBookId()
        if (bookId.isBlank()) return@runCatching BookVolumes.empty(id)
        val document = jsoup.getDocument(LinovelibConstants.catalogUrl(bookId), referer = LinovelibConstants.detailUrl(bookId))
        val volumes = parseVolumes(document, bookId)
        if (volumes.isEmpty()) BookVolumes.empty(bookId) else BookVolumes(bookId, volumes)
    }.getOrElse {
        if (it is CancellationException) throw it
        it.printStackTrace()
        BookVolumes.empty(id)
    }

    suspend fun getChapterContent(chapterId: String, bookId: String): ChapterContent = runCatching {
        val normalizedBookId = bookId.normalizeBookId()
        val normalizedChapterId = chapterId.normalizeChapterId()
        if (normalizedBookId.isBlank() || normalizedChapterId.isBlank()) {
            return@runCatching ChapterContent.empty(chapterId)
        }
        val builder = ContentBuilder()
        val parserWarnings = mutableListOf<String>()
        val seenPageSignatures = mutableSetOf<String>()
        val chapterParts = mutableListOf<LinovelibChapterContentParser.Part>()
        val pageBoundaries = mutableListOf<LinovelibChapterPageBoundary>()
        val baseChapterId = normalizedChapterId.substringBefore('_')
        var accumulatedPageWeight = 0
        var title = ""
        var lastChapterId = ""
        var nextChapterId = ""
        var foundFirstPrevPageScript = false
        var foundTerminalNextPageScript = false
        var page = 1
        var pageChapterId = normalizedChapterId
        var previousPageChapterId = ""
        while (page <= MAX_CHAPTER_PAGE) {
            val currentPageChapterId = pageChapterId
            val document = jsoup.getDocument(
                LinovelibConstants.chapterUrl(normalizedBookId, currentPageChapterId),
                referer = LinovelibConstants.catalogUrl(normalizedBookId),
                retryTime = if (page == 1) 2 else 1
            )
            val scriptPrevPage = extractLinovelibScriptPage(document, "prevpage")
            val scriptPrevPageId = scriptPrevPage?.let { extractLinovelibChapterPageId(normalizedBookId, it) }
            if (page == 1) {
                foundFirstPrevPageScript = !scriptPrevPage.isNullOrBlank()
                lastChapterId = scriptPrevPageId?.toLinovelibAdjacentChapterId(baseChapterId).orEmpty()
            } else if (scriptPrevPageId != null && scriptPrevPageId != previousPageChapterId) {
                error("Linovelib chapter $normalizedBookId/$normalizedChapterId page $currentPageChapterId has unexpected prevpage $scriptPrevPageId")
            }
            if (title.isBlank()) title = document.firstText("h1", ".chapter-title", ".bookname h1") ?: ""
            val content = document.selectFirst("#TextContent") ?: document.selectFirst("#textcontent")
            ?: document.selectFirst(".chapter-content") ?: document.selectFirst("#content")
            ?: if (page == 1) return@runCatching ChapterContent.empty(normalizedChapterId) else error("Linovelib chapter $normalizedBookId/$normalizedChapterId page $currentPageChapterId has no content")
            val signature = content.linovelibChapterPageSignature()
            if (signature.isBlank() || !seenPageSignatures.add(signature)) {
                if (page == 1) break
                error("Linovelib chapter $normalizedBookId/$normalizedChapterId page $currentPageChapterId is empty or duplicated")
            }
            val parseResult = LinovelibChapterContentParser.parse(content, currentPageChapterId) { it.imageUrl() }
            parseResult.warning?.let(parserWarnings::add)
            val pageWeight = parseResult.parts.linovelibContentWeight().coerceAtLeast(1)
            pageBoundaries += LinovelibChapterPageBoundary(
                chapterId = currentPageChapterId,
                startWeight = accumulatedPageWeight,
                endWeight = accumulatedPageWeight + pageWeight
            )
            accumulatedPageWeight += pageWeight
            chapterParts.addAll(parseResult.parts)
            val scriptNextPage = extractLinovelibScriptPage(document, "nextpage")
            val scriptNextPageId = scriptNextPage?.let { extractLinovelibChapterPageId(normalizedBookId, it) }
            val nextPageChapterId = document.nextLinovelibChapterPageId(normalizedBookId, baseChapterId, page + 1)
            if (nextPageChapterId == null) {
                foundTerminalNextPageScript = !scriptNextPage.isNullOrBlank()
                nextChapterId = scriptNextPageId?.toLinovelibAdjacentChapterId(baseChapterId).orEmpty()
                break
            }
            if (page >= MAX_CHAPTER_PAGE) error("Linovelib chapter $normalizedBookId/$normalizedChapterId exceeds $MAX_CHAPTER_PAGE pages")
            previousPageChapterId = currentPageChapterId
            pageChapterId = nextPageChapterId
            page++
        }
        val mergedParts = chapterParts.mergeLinovelibPagedTextParts()
        mergedParts.forEachIndexed { index, part ->
            when (part) {
                is LinovelibChapterContentParser.Part.Text -> builder.component(part.toLinovelibSimpleTextComponentData())
                is LinovelibChapterContentParser.Part.Image -> builder.image(
                    uri = part.url.toUri(),
                    topPaddingDp = mergedParts.linovelibImageTopPaddingDp(index),
                    bottomPaddingDp = mergedParts.linovelibImageBottomPaddingDp(index)
                )
                LinovelibChapterContentParser.Part.SectionBreak -> Unit
            }
        }
        val content = builder.build()
            .withLinovelibChapterPageMap(pageBoundaries)
            .withParserWarnings(parserWarnings)
        val cleanTitle = title.cleanText()
        val navigation = if (cleanTitle.isBlank() || !foundFirstPrevPageScript || !foundTerminalNextPageScript) {
            getChapterNavigation(normalizedBookId, normalizedChapterId)
        } else {
            ChapterNavigation()
        }
        MutableChapterContent(
            id = normalizedChapterId,
            title = cleanTitle.ifBlank { navigation.currentTitle },
            content = content,
            lastChapter = lastChapterId.ifBlank { navigation.lastChapterId },
            nextChapter = nextChapterId.ifBlank { navigation.nextChapterId }
        ).takeIf { !it.isEmpty() } ?: ChapterContent.empty(normalizedChapterId)
    }.getOrElse {
        if (it is CancellationException) throw it
        it.printStackTrace()
        ChapterContent.empty(chapterId)
    }

    fun parseSearchBooks(document: Document): List<BookInformation> {
        val elements = document.select(".book-list li, .search-result li, .bookbox, .book-item, .book-layout")
            .ifEmpty { document.select("a[href~=/novel/\\d+\\.html]").map { it.parent() ?: it } }
        return elements.mapNotNull { element ->
            val href = element.selectFirst("a[href~=/novel/\\d+\\.html]")?.attr("href") ?: return@mapNotNull null
            val bookId = href.extractBookId() ?: return@mapNotNull null
            val title = element.selectFirst("a[href~=/novel/\\d+\\.html]")?.text()?.cleanText().orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val cover = element.selectFirst("img")?.imageUrl().orEmpty()
            MutableBookInformation(
                id = bookId,
                title = title,
                subtitle = "",
                coverUrl = cover.takeIf { it.isNotBlank() }?.toUri() ?: Uri.EMPTY,
                author = element.textAfterLabel("作者") ?: "",
                description = element.selectFirst(".desc, .intro, p")?.text()?.cleanDescription() ?: "",
                tags = emptyList(),
                publishingHouse = "",
                wordCount = WordCount(0),
                lastUpdated = LocalDateTime.MIN,
                isComplete = element.text().contains("完结")
            )
        }.distinctBy { it.id }
    }

    fun parseExploreBooks(document: Document): List<LinovelibExploreBook> = document
        .select("a[href~=/novel/\\d+\\.html]")
        .mapNotNull { a ->
            val href = a.attr("href")
            val bookId = href.extractBookId() ?: return@mapNotNull null
            val image = a.selectFirst("img") ?: a.parentBookCard(bookId)?.selectFirst("img")
            val container = image?.parents()?.firstOrNull { it.hasSingleExploreBook(bookId) } ?: a.parent() ?: a
            val title = a.text().cleanText()
                .ifBlank { a.attr("title").cleanText() }
                .ifBlank { image?.attr("alt")?.cleanText().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            LinovelibExploreBook(
                id = bookId,
                title = title,
                author = container.textAfterLabel("作者") ?: "",
                coverUrl = image?.imageUrl().orEmpty()
            )
        }
        .distinctBy { it.id }

    internal suspend fun parseVolumes(
        document: Document,
        bookId: String,
        missingChapterIdResolver: suspend (previousChapterId: String?, nextChapterId: String?) -> String? = { previousChapterId, nextChapterId ->
            resolveMissingCatalogChapterId(bookId, previousChapterId, nextChapterId)
        }
    ): List<Volume> {
        val volumeElements = document.select(
            "#volume-list .volume, .volume-list .volume, .catalog-volume, .chapter-list .volume, .volume-item"
        )
        if (volumeElements.isNotEmpty()) {
            val volumeCandidates = volumeElements.map { element ->
                element to element.select("a[href]").mapNotNull { it.toCatalogChapterCandidate(bookId) }
            }
            val resolvedChaptersByVolume = volumeCandidates
                .map { it.second }
                .resolveMissingCatalogChapters(missingChapterIdResolver)
            val volumes = volumeCandidates.zip(resolvedChaptersByVolume).mapIndexedNotNull { index, (volume, chapters) ->
                if (chapters.isEmpty()) return@mapIndexedNotNull null
                Volume(
                    volumeId = "${bookId}_$index",
                    volumeTitle = volume.first.volumeTitle() ?: "第 ${index + 1} 卷",
                    chapters = chapters
                )
            }
            if (volumes.isNotEmpty()) return volumes
        }
        val chapters = listOf(
            document.select("#volume-list a[href], #chapter-list a[href], .chapter-list a[href], .catalog a[href], a[href]")
                .mapNotNull { it.toCatalogChapterCandidate(bookId) }
        ).resolveMissingCatalogChapters(missingChapterIdResolver).single()
        return if (chapters.isEmpty()) emptyList() else listOf(Volume(bookId, "正文", chapters))
    }

    private suspend fun resolveMissingCatalogChapterId(
        bookId: String,
        previousChapterId: String?,
        nextChapterId: String?
    ): String? {
        nextChapterId?.let { id ->
            resolveMissingCatalogChapterIdFromNext(bookId, id)?.let { return it }
        }
        return previousChapterId?.let { resolveMissingCatalogChapterIdFromPrevious(bookId, it) }
    }

    private suspend fun resolveMissingCatalogChapterIdFromNext(bookId: String, nextChapterId: String): String? =
        runCatching {
            val document = jsoup.getDocument(
                LinovelibConstants.chapterUrl(bookId, nextChapterId),
                referer = LinovelibConstants.catalogUrl(bookId),
                retryTime = 1
            )
            extractLinovelibScriptPage(document, "prevpage")
                ?.let { extractLinovelibChapterPageId(bookId, it) }
                ?.toLinovelibAdjacentChapterId(nextChapterId.substringBefore('_'))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }

    private suspend fun resolveMissingCatalogChapterIdFromPrevious(bookId: String, previousChapterId: String): String? =
        runCatching {
            val baseChapterId = previousChapterId.substringBefore('_')
            var page = 1
            var pageChapterId = previousChapterId
            while (page <= MAX_CHAPTER_PAGE) {
                val document = jsoup.getDocument(
                    LinovelibConstants.chapterUrl(bookId, pageChapterId),
                    referer = LinovelibConstants.catalogUrl(bookId),
                    retryTime = if (page == 1) 2 else 1
                )
                val scriptNextPageId = extractLinovelibScriptPage(document, "nextpage")
                    ?.let { extractLinovelibChapterPageId(bookId, it) }
                val nextPageChapterId = document.nextLinovelibChapterPageId(bookId, baseChapterId, page + 1)
                if (nextPageChapterId == null) {
                    return@runCatching scriptNextPageId?.toLinovelibAdjacentChapterId(baseChapterId)
                }
                pageChapterId = nextPageChapterId
                page++
            }
            null
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            null
        }

    private suspend fun List<List<LinovelibCatalogChapterCandidate>>.resolveMissingCatalogChapters(
        missingChapterIdResolver: suspend (previousChapterId: String?, nextChapterId: String?) -> String?
    ): List<List<ChapterInformation>> {
        val sizes = map { it.size }
        val resolved = flatten().resolveMissingCatalogCandidates(missingChapterIdResolver)
        var start = 0
        return sizes.map { size ->
            val chapters = resolved
                .subList(start, start + size)
                .mapNotNull { it.toChapterInformation() }
                .distinctBy { it.id }
            start += size
            chapters
        }
    }

    private suspend fun List<LinovelibCatalogChapterCandidate>.resolveMissingCatalogCandidates(
        missingChapterIdResolver: suspend (previousChapterId: String?, nextChapterId: String?) -> String?
    ): List<LinovelibCatalogChapterCandidate> {
        val resolved = toMutableList()

        suspend fun resolveAt(index: Int): Boolean {
            val candidate = resolved[index]
            if (candidate.id != null) return false
            val previousChapterId = resolved.getOrNull(index - 1)?.id
            val nextChapterId = resolved.getOrNull(index + 1)?.id
            if (previousChapterId == null && nextChapterId == null) return false
            val resolvedId = missingChapterIdResolver(previousChapterId, nextChapterId)
                ?.normalizeChapterId()
                ?.substringBefore('_')
                ?.takeIf { id -> id.isNotBlank() && id.all { it.isDigit() } }
                ?.takeIf { id -> id != previousChapterId && id != nextChapterId }
                ?.takeIf { id -> resolved.none { it.id == id } }
                ?: return false
            resolved[index] = candidate.copy(id = resolvedId)
            return true
        }

        do {
            var changed = false
            for (index in resolved.indices.reversed()) changed = resolveAt(index) || changed
            for (index in resolved.indices) changed = resolveAt(index) || changed
        } while (changed)

        return resolved
    }

    private suspend fun getChapterNavigation(bookId: String, chapterId: String): ChapterNavigation {
        val chapters = getBookVolumes(bookId).volumes.flatMap { it.chapters }
        val index = chapters.indexOfFirst { it.id == chapterId }
        if (index < 0) return ChapterNavigation()
        return ChapterNavigation(
            currentTitle = chapters[index].title,
            lastChapterId = chapters.getOrNull(index - 1)?.id ?: "",
            nextChapterId = chapters.getOrNull(index + 1)?.id ?: ""
        )
    }

    private fun JsonObject.withParserWarnings(warnings: List<String>): JsonObject {
        val warning = warnings.distinct().joinToString("\n").takeIf { it.isNotBlank() } ?: return this
        return buildJsonObject {
            this@withParserWarnings.forEach { (key, value) -> put(key, value) }
            put(LinovelibChapterContentParser.WARNING_KEY, warning)
        }
    }

    private fun Element.toCatalogChapterCandidate(bookId: String): LinovelibCatalogChapterCandidate? {
        val href = attr("href")
        if (href.isLinovelibVolumeHref()) return null
        val title = text().cleanText().ifBlank { attr("title").cleanText() }
        if (title.isBlank()) return null
        if (href.contains("cid(0)", ignoreCase = true)) {
            return LinovelibCatalogChapterCandidate(id = null, title = title)
        }
        if (href.startsWith("javascript:", ignoreCase = true)) return null
        val id = extractLinovelibChapterPageId(bookId, href)?.takeUnless { '_' in it } ?: return null
        return LinovelibCatalogChapterCandidate(id, title)
    }

    private fun Element.parentBookCard(bookId: String): Element? = parents().firstOrNull { it.hasSingleExploreBook(bookId) }

    private fun Element.hasSingleExploreBook(bookId: String): Boolean {
        if (selectFirst("img") == null) return false
        val bookIds = select("a[href~=/novel/\\d+\\.html]")
            .mapNotNull { it.attr("href").extractBookId() }
            .distinct()
        return bookIds.size == 1 && bookIds.single() == bookId
    }

    private fun Document.metaContent(name: String): String? =
        selectFirst("meta[name=\"$name\"]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: selectFirst("meta[property=\"$name\"]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun Document.firstText(vararg selectors: String): String? = selectors.firstNotNullOfOrNull { selector ->
        selectFirst(selector)?.text()?.cleanText()?.takeIf { it.isNotBlank() }
    }

    private fun Document.firstCoverImageUrl(): String? = COVER_IMAGE_SELECTORS.firstNotNullOfOrNull { selector ->
        selectFirst(selector)?.imageUrl()?.takeIf { it.isNotBlank() }
    }

    private fun Element.volumeTitle(): String? = VOLUME_TITLE_SELECTORS.firstNotNullOfOrNull { selector ->
        selectFirst(selector)?.text()?.cleanText()?.takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String {
        IMAGE_URL_ATTRS.forEach { attrName ->
            val url = absUrl(attrName).ifBlank { attr(attrName) }.toRealImageUrl()
            if (url.isNotBlank()) return url
        }
        attr("srcset")
            .split(",")
            .asSequence()
            .map { it.trim().substringBefore(" ") }
            .map { it.toRealImageUrl() }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it }
        return ""
    }

    private fun String.toRealImageUrl(): String {
        val normalizedUrl = LinovelibJsoup.normalizeUrl(this)
        val lowerUrl = normalizedUrl.lowercase()
        return normalizedUrl.takeIf {
            lowerUrl.isNotBlank() &&
                "loading." !in lowerUrl &&
                "placeholder" !in lowerUrl &&
                !lowerUrl.endsWith("/blank.gif") &&
                !lowerUrl.endsWith("/spacer.gif") &&
                !lowerUrl.endsWith("/transparent.png")
        }.orEmpty()
    }

    private fun Document.labelValue(label: String): String? = body().textAfterLabel(label)

    private fun Element.textAfterLabel(label: String): String? {
        val regex = Regex("$label[：:]\\s*([^\\s　/／|｜]+(?:\\s+[^\\s　/／|｜]+){0,4})")
        return regex.find(text())?.groups?.get(1)?.value?.cleanText()
    }

    private fun String.extractBookId(): String? = Regex("/novel/(\\d+)(?:\\.html|/|$)").find(this)?.groups?.get(1)?.value

    private fun String.isLinovelibVolumeHref(): Boolean {
        val fileName = trim()
            .replace("\\/", "/")
            .substringBefore('#')
            .substringBefore('?')
            .replace('\\', '/')
            .substringAfterLast('/')
            .lowercase()
        return fileName.startsWith("vol_") && fileName.endsWith(".html")
    }

    private fun String.normalizeBookId(): String = trim().substringBefore('.').substringAfterLast('/').filter { it.isDigit() }

    private fun String.normalizeChapterId(): String = trim().substringBefore('.').substringAfterLast('/').filter { it.isDigit() || it == '_' }

    private fun String.cleanDescription(): String = cleanText()
        .removePrefix("简介：")
        .removePrefix("内容简介：")
        .trim()

    private fun String.cleanText(): String = replace(' ', ' ')
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun String.parseWordCount(): Int {
        val number = Regex("(\\d+(?:\\.\\d+)?)\\s*([万千]?)").find(this) ?: return 0
        val value = number.groups[1]?.value?.toFloatOrNull() ?: return 0
        return when (number.groups[2]?.value) {
            "万" -> (value * 10_000).toInt()
            "千" -> (value * 1_000).toInt()
            else -> value.toInt()
        }
    }

    private fun String.parseDateTime(): LocalDateTime {
        val dateText = Regex("\\d{4}-\\d{1,2}-\\d{1,2}").find(this)?.value ?: return LocalDateTime.MIN
        return runCatching { LocalDate.parse(dateText, dateFormatter).atStartOfDay() }.getOrDefault(LocalDateTime.MIN)
    }

    private data class ChapterNavigation(
        val currentTitle: String = "",
        val lastChapterId: String = "",
        val nextChapterId: String = ""
    )

    private data class LinovelibCatalogChapterCandidate(
        val id: String?,
        val title: String
    ) {
        fun toChapterInformation(): ChapterInformation? = id?.let { ChapterInformation(it, title) }
    }

    data class LinovelibExploreBook(
        val id: String,
        val title: String,
        val author: String,
        val coverUrl: String
    )

    companion object {
        private const val MAX_CHAPTER_PAGE = 30
        private val COVER_IMAGE_SELECTORS = listOf(
            ".book-img img",
            ".book-cover img",
            ".book-info img",
            "img[src*=cover]",
            "img[data-src*=cover]"
        )
        private val VOLUME_TITLE_SELECTORS = listOf("h2", "h3", ".volume-title", ".title")
        private val IMAGE_URL_ATTRS = listOf(
            "data-src",
            "data-original",
            "data-original-url",
            "data-url",
            "data-lazy-src",
            "data-echo",
            "data-cover",
            "src"
        )
    }
}

internal fun extractLinovelibScriptPage(document: Document, name: String): String? {
    val regex = Regex("""\b(?:var\s+)?${Regex.escape(name)}\s*=\s*["']([^"']*)["']""")
    return document.select("script")
        .asSequence()
        .map { script -> script.data().ifBlank { script.html() } }
        .mapNotNull { script -> regex.find(script)?.groups?.get(1)?.value }
        .firstOrNull { it.isNotBlank() }
}

internal fun extractLinovelibChapterPageId(bookId: String, url: String): String? {
    val normalizedUrl = url.trim()
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .substringBefore('#')
        .substringBefore('?')
        .replace('\\', '/')
    if (normalizedUrl.isBlank() || normalizedUrl.startsWith("javascript:", ignoreCase = true)) return null

    val fullPathMatch = Regex("""(?:^|/)novel/(\d+)/(\d+(?:_\d+)?)\.html$""").find(normalizedUrl)
    if (fullPathMatch != null) {
        val matchedBookId = fullPathMatch.groups[1]?.value ?: return null
        val pageId = fullPathMatch.groups[2]?.value ?: return null
        return pageId.takeIf { matchedBookId == bookId }
    }
    if ("/novel/" in normalizedUrl || normalizedUrl.startsWith("novel/")) return null

    val fileName = normalizedUrl.substringAfterLast('/')
    return Regex("""\d+(?:_\d+)?\.html""")
        .matchEntire(fileName)
        ?.value
        ?.removeSuffix(".html")
}

internal fun isLinovelibPagedChapterId(baseChapterId: String, pageId: String): Boolean {
    val pagePrefix = "${baseChapterId}_"
    val pageSuffix = pageId.removePrefix(pagePrefix)
    return pageId.startsWith(pagePrefix) && pageSuffix.isNotEmpty() && pageSuffix.all { it.isDigit() }
}

internal fun String.toLinovelibAdjacentChapterId(baseChapterId: String): String? {
    val adjacentBaseChapterId = substringBefore('_')
    return adjacentBaseChapterId.takeIf {
        it.isNotBlank() && it != baseChapterId && it.all { char -> char.isDigit() }
    }
}

internal fun Document.nextLinovelibChapterPageId(
    bookId: String,
    baseChapterId: String,
    expectedPage: Int
): String? {
    val expectedPageId = linovelibChapterPageId(baseChapterId, expectedPage)
    val scriptedNextPage = extractLinovelibScriptPage(this, "nextpage")
    if (!scriptedNextPage.isNullOrBlank()) {
        val scriptedPageId = extractLinovelibChapterPageId(bookId, scriptedNextPage)
        if (scriptedPageId != null) {
            return scriptedPageId.takeIf { it == expectedPageId && isLinovelibPagedChapterId(baseChapterId, it) }
        }
    }
    return select("a[href]")
        .asSequence()
        .flatMap { element -> sequenceOf(element.attr("href"), element.absUrl("href")) }
        .mapNotNull { href -> extractLinovelibChapterPageId(bookId, href) }
        .firstOrNull { it == expectedPageId && isLinovelibPagedChapterId(baseChapterId, it) }
}

internal fun Element.linovelibChapterPageSignature(): String {
    val normalizedText = text().cleanLinovelibPageText()
        .ifBlank { html().cleanLinovelibPageText() }
    if (normalizedText.isBlank()) return ""
    return "${normalizedText.length}:${normalizedText.take(300)}:${normalizedText.takeLast(300)}"
}

private fun linovelibChapterPageId(baseChapterId: String, page: Int): String =
    if (page <= 1) baseChapterId else "${baseChapterId}_$page"

private fun String.cleanLinovelibPageText(): String = replace(' ', ' ')
    .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

internal fun List<LinovelibChapterContentParser.Part>.mergeLinovelibPagedTextParts(): List<LinovelibChapterContentParser.Part> {
    val merged = mutableListOf<LinovelibChapterContentParser.Part>()
    val pendingText = StringBuilder()
    val pendingStyleRanges = mutableListOf<SimpleTextStyleRange>()
    var hasPendingSectionBreak = false

    fun appendTextPart(part: LinovelibChapterContentParser.Part.Text) {
        val offset = pendingText.length
        pendingText.append(part.text)
        pendingStyleRanges += part.styleRanges.map { range ->
            range.copy(start = range.start + offset, end = range.end + offset)
        }
    }

    fun flushText() {
        val text = pendingText.toString()
        if (text.isNotBlank()) {
            merged.add(LinovelibChapterContentParser.Part.Text(text, pendingStyleRanges.toList()))
        }
        pendingText.clear()
        pendingStyleRanges.clear()
    }

    fun flushTrailingSectionBreak() {
        flushText()
        if (merged.isNotEmpty() && merged.last() != LinovelibChapterContentParser.Part.SectionBreak) {
            merged.add(LinovelibChapterContentParser.Part.SectionBreak)
        }
        hasPendingSectionBreak = false
    }

    forEach { part ->
        when (part) {
            is LinovelibChapterContentParser.Part.Text -> {
                if (pendingText.isNotBlank()) {
                    pendingText.append(
                        if (hasPendingSectionBreak) {
                            LinovelibChapterContentParser.SECTION_SEPARATOR
                        } else {
                            LinovelibChapterContentParser.PARAGRAPH_SEPARATOR
                        }
                    )
                } else if (merged.lastOrNull() == LinovelibChapterContentParser.Part.SectionBreak) {
                    merged.removeAt(merged.lastIndex)
                    pendingText.append(LinovelibChapterContentParser.SECTION_SEPARATOR)
                }
                appendTextPart(part)
                hasPendingSectionBreak = false
            }
            is LinovelibChapterContentParser.Part.Image -> {
                hasPendingSectionBreak = false
                flushText()
                merged.add(part)
            }
            LinovelibChapterContentParser.Part.SectionBreak -> {
                if (pendingText.isNotBlank()) {
                    hasPendingSectionBreak = true
                } else if (
                    merged.isNotEmpty() &&
                    merged.last() != LinovelibChapterContentParser.Part.SectionBreak &&
                    merged.last() !is LinovelibChapterContentParser.Part.Image
                ) {
                    merged.add(LinovelibChapterContentParser.Part.SectionBreak)
                }
            }
        }
    }
    if (hasPendingSectionBreak) flushTrailingSectionBreak() else flushText()
    return merged
}

internal fun List<LinovelibChapterContentParser.Part>.linovelibImageTopPaddingDp(index: Int): Int =
    if (getOrNull(index - 1) is LinovelibChapterContentParser.Part.Text) {
        ImageComponentData.DEFAULT_TOP_PADDING_DP
    } else {
        0
    }

internal fun List<LinovelibChapterContentParser.Part>.linovelibImageBottomPaddingDp(index: Int): Int =
    if (getOrNull(index + 1) is LinovelibChapterContentParser.Part.Text) {
        ImageComponentData.DEFAULT_BOTTOM_PADDING_DP
    } else {
        0
    }

internal fun LinovelibChapterContentParser.Part.Text.toLinovelibSimpleTextComponentData(): SimpleTextComponentData {
    val rendered = text.renderLinovelibSpacingWithSourceMap()
    return SimpleTextComponentData(
        text = rendered.text,
        styleRanges = styleRanges.remapLinovelibStyleRanges(rendered.sourceIndices)
    )
}

internal fun String.renderLinovelibSpacing(): String = renderLinovelibSpacingWithSourceMap().text

private fun String.renderLinovelibSpacingWithSourceMap(): LinovelibTextWithSourceMap {
    val chars = mutableListOf<Char>()
    val sourceIndices = mutableListOf<Int?>()
    var index = 0
    var paragraphStart = true

    fun appendIndent() {
        LINOVELIB_PARAGRAPH_INDENT.forEach { char ->
            chars.add(char)
            sourceIndices.add(null)
        }
    }

    fun appendText(value: String, sourceIndex: (Int) -> Int?) {
        value.forEachIndexed { offset, char ->
            chars.add(char)
            sourceIndices.add(sourceIndex(offset))
        }
    }

    while (index < length) {
        if (paragraphStart && this[index] != '\n') {
            appendIndent()
            paragraphStart = false
        }
        when {
            startsWith(LinovelibChapterContentParser.SECTION_SEPARATOR, index) -> {
                appendText(LINOVELIB_DISPLAY_SECTION_SEPARATOR) { offset ->
                    when (offset) {
                        0 -> index
                        1 -> index + 1
                        3 -> index + 2
                        else -> null
                    }
                }
                index += LinovelibChapterContentParser.SECTION_SEPARATOR.length
                paragraphStart = true
            }
            startsWith(LinovelibChapterContentParser.PARAGRAPH_SEPARATOR, index) -> {
                appendText(LinovelibChapterContentParser.PARAGRAPH_SEPARATOR) { offset -> index + offset }
                index += LinovelibChapterContentParser.PARAGRAPH_SEPARATOR.length
                paragraphStart = true
            }
            else -> {
                chars.add(this[index])
                sourceIndices.add(index)
                index++
            }
        }
    }
    return LinovelibTextWithSourceMap(chars.joinToString(""), sourceIndices)
}

private const val LINOVELIB_PARAGRAPH_INDENT = "　　"
private const val LINOVELIB_DISPLAY_SECTION_SEPARATOR = "\n\n \n"
