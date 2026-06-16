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
import io.nightfish.lightnovelreader.api.content.builder.simpleText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
        val baseChapterId = normalizedChapterId.substringBefore('_')
        var title = ""
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
            if (page > 1) {
                val prevPageChapterId = document.linovelibScriptChapterPageId(normalizedBookId, "prevpage")
                if (prevPageChapterId != null && prevPageChapterId != previousPageChapterId) {
                    error("Linovelib chapter $normalizedBookId/$normalizedChapterId page $currentPageChapterId has unexpected prevpage $prevPageChapterId")
                }
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
            chapterParts.addAll(parseResult.parts)
            val nextPageChapterId = document.nextLinovelibChapterPageId(normalizedBookId, baseChapterId, page + 1) ?: break
            if (page >= MAX_CHAPTER_PAGE) error("Linovelib chapter $normalizedBookId/$normalizedChapterId exceeds $MAX_CHAPTER_PAGE pages")
            previousPageChapterId = currentPageChapterId
            pageChapterId = nextPageChapterId
            page++
        }
        chapterParts.mergeLinovelibPagedTextParts().forEach { part ->
            when (part) {
                is LinovelibChapterContentParser.Part.Text -> builder.simpleText(part.text)
                is LinovelibChapterContentParser.Part.Image -> part.url.toUri().let(builder::image)
            }
        }
        val content = builder.build().withParserWarnings(parserWarnings)
        val navigation = getChapterNavigation(normalizedBookId, normalizedChapterId)
        MutableChapterContent(
            id = normalizedChapterId,
            title = title.cleanText().ifBlank { navigation.currentTitle },
            content = content,
            lastChapter = navigation.lastChapterId,
            nextChapter = navigation.nextChapterId
        ).takeIf { !it.isEmpty() } ?: ChapterContent.empty(normalizedChapterId)
    }.getOrElse {
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
            val bookId = a.attr("href").extractBookId() ?: return@mapNotNull null
            val container = a.parents().firstOrNull { it.selectFirst("img") != null } ?: a.parent() ?: a
            val title = a.text().cleanText().ifBlank { a.attr("title").cleanText() }
            if (title.isBlank()) return@mapNotNull null
            val cover = container.selectFirst("img")?.imageUrl().orEmpty()
            LinovelibExploreBook(
                id = bookId,
                title = title,
                author = container.textAfterLabel("作者") ?: "",
                coverUrl = cover
            )
        }
        .distinctBy { it.id }

    private fun parseVolumes(document: Document, bookId: String): List<Volume> {
        val volumeElements = document.select("#volume-list .volume, .volume-list .volume, .catalog-volume, .chapter-list .volume")
        if (volumeElements.isNotEmpty()) {
            val volumes = volumeElements.mapIndexedNotNull { index, element ->
                val chapters = element.select("a[href]")
                    .mapNotNull { it.toChapterInformation(bookId) }
                    .distinctBy { it.id }
                if (chapters.isEmpty()) return@mapIndexedNotNull null
                Volume(
                    volumeId = "${bookId}_$index",
                    volumeTitle = element.volumeTitle() ?: "第 ${index + 1} 卷",
                    chapters = chapters
                )
            }
            if (volumes.isNotEmpty()) return volumes
        }
        val chapters = document.select("#volume-list a[href], #chapter-list a[href], .chapter-list a[href], .catalog a[href], a[href]")
            .mapNotNull { it.toChapterInformation(bookId) }
            .distinctBy { it.id }
        return if (chapters.isEmpty()) emptyList() else listOf(Volume(bookId, "正文", chapters))
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

    private fun Element.toChapterInformation(bookId: String): ChapterInformation? {
        val href = attr("href")
        if (href.startsWith("javascript:") || "cid(0)" in href || "vol_" in href) return null
        if (Regex("/novel/${Regex.escape(bookId)}/\\d+_\\d+\\.html").containsMatchIn(href)) return null
        val id = href.extractChapterId(bookId) ?: return null
        val title = text().cleanText().ifBlank { attr("title").cleanText() }
        if (title.isBlank()) return null
        return ChapterInformation(id, title)
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

    private fun String.extractChapterId(bookId: String): String? =
        Regex("/novel/${Regex.escape(bookId)}/(\\d+)\\.html").find(this)?.groups?.get(1)?.value

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

private fun Document.linovelibScriptChapterPageId(bookId: String, name: String): String? =
    extractLinovelibScriptPage(this, name)?.let { extractLinovelibChapterPageId(bookId, it) }

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

    fun flushText() {
        val text = pendingText.toString()
        pendingText.clear()
        if (text.isNotBlank()) merged.add(LinovelibChapterContentParser.Part.Text(text))
    }

    forEach { part ->
        when (part) {
            is LinovelibChapterContentParser.Part.Text -> {
                if (pendingText.isNotBlank()) pendingText.append("\n\n")
                pendingText.append(part.text)
            }
            is LinovelibChapterContentParser.Part.Image -> {
                flushText()
                merged.add(part)
            }
        }
    }
    flushText()
    return merged
}
