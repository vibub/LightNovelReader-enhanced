package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal object LinovelibChapterContentParser {
    const val WARNING_KEY = "linovelibParserWarning"
    const val WARNING_MESSAGE = "Linovelib 本章段落顺序还原失败，已停止使用网页源码乱序正文。请刷新章节或反馈该章节 ID。"

    const val PARAGRAPH_SEPARATOR = "\n\n"// 普通段落之间的分隔。
    const val SECTION_SEPARATOR = "\n\n\n"// 解析层识别到“小节分隔”后使用的内部语义分隔。

    sealed interface Part {
        data class Text(
            val text: String,
            val styleRanges: List<SimpleTextStyleRange> = emptyList()
        ) : Part
        data class Image(val url: String) : Part
        data object SectionBreak : Part
    }

    data class ParseResult(
        val parts: List<Part>,
        val warning: String? = null
    )

    fun parse(content: Element, chapterId: String? = null, imageUrl: (Element) -> String): ParseResult {
        val orderedContent = content.orderedContent(chapterId)
        if (orderedContent is OrderedContent.Failed) {
            return ParseResult(listOf(Part.Text(orderedContent.warning)), orderedContent.warning)
        }
        val orderedNodes = orderedContent as OrderedContent.Nodes
        val parts = buildList {
            val pendingText = StyledTextBuilder()

            fun flushText() {
                val textPart = pendingText.toCleanTextPart()
                pendingText.clear()
                if (textPart != null && !textPart.text.isNoiseText()) add(textPart)
            }

            fun appendSectionBreak() {
                flushText()
                if (isNotEmpty() && last() != Part.SectionBreak) add(Part.SectionBreak)
            }

            fun appendNode(node: Node) {
                when (node) {
                    is TextNode -> pendingText.append(node.text())
                    is Element -> when {
                        node.`is`("script, style, noscript") -> Unit
                        node.`is`("br") -> pendingText.append('\n')
                        node.`is`("img") -> {
                            flushText()
                            imageUrl(node).takeIf { it.isNotBlank() }?.let { add(Part.Image(it)) }
                        }
                        else -> {
                            val pendingStart = pendingText.length
                            val styleStart = pendingText.length
                            node.childNodes().forEach(::appendNode)
                            node.inlineTextStyle()?.let { style ->
                                pendingText.addStyle(styleStart, pendingText.length, style)
                            }
                            if (node.isBlockTextElement()) {
                                if (node.isBlankBlockTextElement()) {
                                    pendingText.truncate(pendingStart)
                                    appendSectionBreak()
                                } else {
                                    pendingText.append('\n')
                                    flushText()
                                }
                            }
                        }
                    }
                }
            }

            fun appendTopLevelNode(node: Node) {
                if (node is Element && node.`is`("br")) {
                    appendSectionBreak()
                } else {
                    appendNode(node)
                }
            }

            orderedNodes.nodes.forEach(::appendTopLevelNode)
            flushText()
        }.mergeAdjacentTextParts()
        return ParseResult(parts, orderedNodes.warning)
    }

    private sealed interface OrderedContent {
        data class Nodes(
            val nodes: List<Node>,
            val warning: String? = null
        ) : OrderedContent

        data class Failed(
            val warning: String
        ) : OrderedContent
    }

    private sealed interface RestoreResult {
        data class Restored(val nodes: List<Node>) : RestoreResult
        object NotApplicable : RestoreResult
        data class Failed(val warning: String) : RestoreResult
    }

    private fun Element.orderedContent(chapterId: String?): OrderedContent {
        val nodes = childNodes()
        val explicitResult = restoreByExplicitOrder(nodes)
        if (explicitResult is RestoreResult.Restored) return OrderedContent.Nodes(explicitResult.nodes)

        val seededResult = restoreByLinovelibSeededOrder(this, chapterId)
        return when (seededResult) {
            is RestoreResult.Restored -> OrderedContent.Nodes(seededResult.nodes)
            is RestoreResult.Failed -> OrderedContent.Failed(seededResult.warning)
            RestoreResult.NotApplicable -> when (explicitResult) {
                is RestoreResult.Failed -> OrderedContent.Failed(explicitResult.warning)
                else -> OrderedContent.Nodes(nodes)
            }
        }
    }

    private fun restoreByExplicitOrder(nodes: List<Node>): RestoreResult {
        val sortableElements = nodes
            .filterIsInstance<Element>()
            .filterNot { it.`is`("script, style, noscript") }
            .filter { it.isOrderableParagraphLikeForOrdering() }
        if (sortableElements.size < 2) return RestoreResult.NotApplicable

        val keyed = sortableElements.map { element -> element to element.explicitOrderKey() }
        if (keyed.none { it.second != null }) return RestoreResult.NotApplicable
        if (keyed.any { it.second == null }) return RestoreResult.Failed(WARNING_MESSAGE)

        val keys = keyed.mapNotNull { it.second }
        if (keys.distinct().size != keys.size) return RestoreResult.Failed(WARNING_MESSAGE)

        val sortedElements = keyed
            .sortedWith(compareBy<Pair<Element, Int?>> { it.second ?: Int.MAX_VALUE })
            .map { it.first }
        val beforeTexts = sortableElements.map { it.cleanOwnTextForCompare() }.sorted()
        val afterTexts = sortedElements.map { it.cleanOwnTextForCompare() }.sorted()
        if (beforeTexts != afterTexts) return RestoreResult.Failed(WARNING_MESSAGE)

        val queue = ArrayDeque(sortedElements)
        val sortedNodes = nodes.map { node ->
            if (node is Element && node.isOrderableParagraphLikeForOrdering() && !node.`is`("script, style, noscript")) {
                queue.removeFirst()
            } else {
                node
            }
        }
        return RestoreResult.Restored(sortedNodes)
    }

    private fun restoreByLinovelibSeededOrder(content: Element, chapterId: String?): RestoreResult {
        val nodes = content.childNodes()
        val paragraphs = content.seededParagraphs()
        if (paragraphs.isEmpty()) return RestoreResult.NotApplicable
        if (paragraphs.size <= LINOVELIB_STABLE_PARAGRAPH_COUNT) return RestoreResult.NotApplicable
        val normalizedChapterId = chapterId
            ?.substringBefore('_')
            ?.toLongOrNull()
            ?: return RestoreResult.Failed(WARNING_MESSAGE)

        val permutation = linovelibParagraphPermutation(paragraphs.size, normalizedChapterId)
        if (permutation.distinct().size != paragraphs.size) return RestoreResult.Failed(WARNING_MESSAGE)

        val restoredParagraphs = MutableList<Element?>(paragraphs.size) { null }
        permutation.forEachIndexed { sourceIndex, targetIndex ->
            if (targetIndex !in restoredParagraphs.indices) return RestoreResult.Failed(WARNING_MESSAGE)
            restoredParagraphs[targetIndex] = paragraphs[sourceIndex]
        }
        if (restoredParagraphs.any { it == null }) return RestoreResult.Failed(WARNING_MESSAGE)

        val restored = restoredParagraphs.filterNotNull()
        val beforeTexts = paragraphs.map { it.cleanOwnTextForCompare() }.sorted()
        val afterTexts = restored.map { it.cleanOwnTextForCompare() }.sorted()
        if (beforeTexts != afterTexts) return RestoreResult.Failed(WARNING_MESSAGE)

        val restoredQueue = ArrayDeque(restored)
        val restoredNodes = nodes.map { it.cloneWithRestoredSeedParagraphs(restoredQueue) }
        if (restoredQueue.isNotEmpty()) return RestoreResult.Failed(WARNING_MESSAGE)
        return RestoreResult.Restored(restoredNodes)
    }

    private fun Element.seededParagraphs(): List<Element> = select("p")
        .filter { it.isSeededParagraphForOrdering() }

    private fun Node.cloneWithRestoredSeedParagraphs(restored: ArrayDeque<Element>): Node = when (this) {
        is Element -> {
            if (isSeededParagraphForOrdering()) {
                if (restored.isEmpty()) clone() else restored.removeFirst().clone()
            } else {
                clone().also { cloned ->
                    cloned.empty()
                    childNodes().forEach { cloned.appendChild(it.cloneWithRestoredSeedParagraphs(restored)) }
                }
            }
        }
        else -> clone()
    }

    private fun Element.isSeededParagraphForOrdering(): Boolean = isNonEmptyParagraph() && !isBlankBlockTextElement()

    private fun Element.isNonEmptyParagraph(): Boolean = `is`("p") && html().replace(Regex("\\s+"), "").isNotEmpty()

    private fun linovelibParagraphPermutation(size: Int, chapterId: Long): List<Int> {
        val fixedCount = minOf(LINOVELIB_STABLE_PARAGRAPH_COUNT, size)
        val shuffled = (fixedCount until size).toMutableList()
        var seed = chapterId * LINOVELIB_SEED_MULTIPLIER + LINOVELIB_SEED_OFFSET
        for (index in shuffled.lastIndex downTo 1) {
            seed = (seed * LINOVELIB_SHUFFLE_MULTIPLIER + LINOVELIB_SHUFFLE_INCREMENT) % LINOVELIB_SHUFFLE_MODULUS
            val swapIndex = ((seed.toDouble() / LINOVELIB_SHUFFLE_MODULUS) * (index + 1)).toInt()
            val value = shuffled[index]
            shuffled[index] = shuffled[swapIndex]
            shuffled[swapIndex] = value
        }
        return (0 until fixedCount).toList() + shuffled
    }

    private fun List<Part>.mergeAdjacentTextParts(): List<Part> {
        val merged = mutableListOf<Part>()
        val pendingText = StyledTextBuilder()
        var hasPendingSectionBreak = false

        fun flushText() {
            pendingText.toTextPart()?.let(merged::add)
            pendingText.clear()
        }

        fun flushTrailingSectionBreak() {
            flushText()
            if (merged.isNotEmpty() && merged.last() != Part.SectionBreak) merged.add(Part.SectionBreak)
            hasPendingSectionBreak = false
        }

        forEach { part ->
            when (part) {
                is Part.Text -> {
                    if (pendingText.isNotBlank()) {
                        pendingText.append(if (hasPendingSectionBreak) SECTION_SEPARATOR else PARAGRAPH_SEPARATOR)
                    } else if (merged.lastOrNull() == Part.SectionBreak) {
                        merged.removeAt(merged.lastIndex)
                        pendingText.append(SECTION_SEPARATOR)
                    }
                    pendingText.append(part)
                    hasPendingSectionBreak = false
                }
                is Part.Image -> {
                    hasPendingSectionBreak = false
                    flushText()
                    merged.add(part)
                }
                Part.SectionBreak -> {
                    if (pendingText.isNotBlank()) {
                        hasPendingSectionBreak = true
                    } else if (merged.isNotEmpty() && merged.last() != Part.SectionBreak && merged.last() !is Part.Image) {
                        merged.add(Part.SectionBreak)
                    }
                }
            }
        }
        if (hasPendingSectionBreak) flushTrailingSectionBreak() else flushText()
        return merged
    }

    private fun Element.explicitOrderKey(): Int? = dataOrderKey() ?: styleOrderKey()

    private fun Element.dataOrderKey(): Int? {
        ORDER_ATTRS.forEach { attrName ->
            attr(attrName).trim().toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun Element.styleOrderKey(): Int? = ORDER_STYLE_REGEX
        .find(attr("style"))
        ?.groups
        ?.get(1)
        ?.value
        ?.toIntOrNull()

    private fun Element.isParagraphLikeForOrdering(): Boolean = `is`("p, div, section, article, center, li")

    private fun Element.isOrderableParagraphLikeForOrdering(): Boolean =
        isParagraphLikeForOrdering() && !isBlankBlockTextElement()

    private fun Element.isBlockTextElement(): Boolean = `is`("p, div, section, article, center, li, blockquote")

    private fun Element.isBlankBlockTextElement(): Boolean = isBlockTextElement() &&
        text().isBlankSpacingText() && childNodes().all { it.isBlankSpacingNode() }

    private fun Node.isBlankSpacingNode(): Boolean = when (this) {
        is TextNode -> text().isBlankSpacingText()
        is Element -> when {
            `is`("script, style, noscript") -> true
            `is`("br") -> true
            `is`("img") -> false
            else -> text().isBlankSpacingText() && childNodes().all { it.isBlankSpacingNode() }
        }
        else -> true
    }

    private fun Element.inlineTextStyle(): SimpleTextStyleRange? {
        val tag = tagName().lowercase()
        var fontWeight: Int? = when (tag) {
            "b", "strong" -> 700
            else -> null
        }
        var italic = tag in setOf("i", "em", "cite")
        var underline = tag in setOf("u", "ins")
        var strikethrough = tag in setOf("s", "strike", "del")
        val style = attr("style").lowercase()
        FONT_WEIGHT_STYLE_REGEX.find(style)?.groups?.get(1)?.value?.trim()?.let { value ->
            fontWeight = when {
                value.contains("bold") -> 700
                value.contains("normal") -> null
                else -> value.toIntOrNull() ?: fontWeight
            }
        }
        if (FONT_STYLE_REGEX.find(style)?.groups?.get(1)?.value?.contains("italic") == true) italic = true
        TEXT_DECORATION_STYLE_REGEX.find(style)?.groups?.get(1)?.value?.let { value ->
            if ("underline" in value) underline = true
            if ("line-through" in value) strikethrough = true
        }
        return if (fontWeight == null && !italic && !underline && !strikethrough) {
            null
        } else {
            SimpleTextStyleRange(
                start = 0,
                end = 0,
                fontWeight = fontWeight,
                italic = italic,
                underline = underline,
                strikethrough = strikethrough
            )
        }
    }

    private fun String.isBlankSpacingText(): Boolean = replace(' ', ' ').isBlank()

    private fun Element.cleanOwnTextForCompare(): String = text().cleanText()

    private fun String.cleanText(): String = cleanTextWithSourceMap().text

    private fun String.cleanTextWithSourceMap(): LinovelibTextWithSourceMap {
        val chars = mutableListOf<Char>()
        val sourceIndices = mutableListOf<Int?>()
        var index = 0
        while (index < length) {
            val char = if (this[index] == ' ') ' ' else this[index]
            when {
                char.isLinovelibCollapsibleSpace() -> {
                    chars.add(' ')
                    sourceIndices.add(index)
                    index++
                    while (index < length) {
                        val next = if (this[index] == ' ') ' ' else this[index]
                        if (!next.isLinovelibCollapsibleSpace()) break
                        index++
                    }
                }
                char == '\n' -> {
                    val start = index
                    while (index < length && this[index] == '\n') index++
                    val count = index - start
                    val outputCount = if (count >= 3) SECTION_SEPARATOR.length else count
                    repeat(outputCount) { offset ->
                        chars.add('\n')
                        sourceIndices.add(start + offset.coerceAtMost(count - 1))
                    }
                }
                else -> {
                    chars.add(char)
                    sourceIndices.add(index)
                    index++
                }
            }
        }
        var start = 0
        var end = chars.size
        while (start < end && chars[start].isWhitespace()) start++
        while (end > start && chars[end - 1].isWhitespace()) end--
        return LinovelibTextWithSourceMap(
            text = chars.subList(start, end).joinToString(""),
            sourceIndices = sourceIndices.subList(start, end).toList()
        )
    }

    private fun Char.isLinovelibCollapsibleSpace(): Boolean = this == ' ' || this == '\t' || this == '' || this == '' || this == '\r'

    private fun String.isNoiseText(): Boolean = contains("最新网址") || contains("请收藏") || contains("本章未完")

    private class StyledTextBuilder {
        private val text = StringBuilder()
        private val styleRanges = mutableListOf<SimpleTextStyleRange>()

        val length: Int get() = text.length

        fun append(value: String) {
            text.append(value)
        }

        fun append(value: Char) {
            text.append(value)
        }

        fun append(part: Part.Text) {
            val offset = text.length
            text.append(part.text)
            styleRanges += part.styleRanges.map { range ->
                range.copy(start = range.start + offset, end = range.end + offset)
            }
        }

        fun addStyle(start: Int, end: Int, style: SimpleTextStyleRange) {
            if (start >= end) return
            styleRanges += style.copy(start = start, end = end)
        }

        fun truncate(length: Int) {
            text.setLength(length)
            val truncatedRanges = styleRanges.mapNotNull { range ->
                when {
                    range.start >= length -> null
                    range.end > length -> range.copy(end = length).takeIf { it.start < it.end }
                    else -> range
                }
            }
            styleRanges.clear()
            styleRanges.addAll(truncatedRanges)
        }

        fun clear() {
            text.clear()
            styleRanges.clear()
        }

        fun isNotBlank(): Boolean = text.isNotBlank()

        fun toCleanTextPart(): Part.Text? {
            val cleaned = text.toString().cleanTextWithSourceMap()
            if (cleaned.text.isBlank()) return null
            return Part.Text(
                text = cleaned.text,
                styleRanges = styleRanges.remapLinovelibStyleRanges(cleaned.sourceIndices)
            )
        }

        fun toTextPart(): Part.Text? {
            val text = text.toString()
            if (text.isBlank()) return null
            return Part.Text(text, styleRanges.toList())
        }
    }

    private const val LINOVELIB_STABLE_PARAGRAPH_COUNT = 20
    private const val LINOVELIB_SEED_MULTIPLIER = 126L
    private const val LINOVELIB_SEED_OFFSET = 232L
    private const val LINOVELIB_SHUFFLE_MULTIPLIER = 9302L
    private const val LINOVELIB_SHUFFLE_INCREMENT = 49397L
    private const val LINOVELIB_SHUFFLE_MODULUS = 233280L

    private val ORDER_STYLE_REGEX = Regex("(?:^|;)\\s*order\\s*:\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
    private val FONT_WEIGHT_STYLE_REGEX = Regex("(?:^|;)\\s*font-weight\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
    private val FONT_STYLE_REGEX = Regex("(?:^|;)\\s*font-style\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
    private val TEXT_DECORATION_STYLE_REGEX = Regex("(?:^|;)\\s*text-decoration(?:-line)?\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
    private val ORDER_ATTRS = listOf(
        "data-order",
        "data-index",
        "data-idx",
        "data-id",
        "data-sort",
        "data-seq"
    )
}

internal data class LinovelibTextWithSourceMap(
    val text: String,
    val sourceIndices: List<Int?>
)

internal fun List<SimpleTextStyleRange>.remapLinovelibStyleRanges(sourceIndices: List<Int?>): List<SimpleTextStyleRange> =
    flatMap { range ->
        val remapped = mutableListOf<SimpleTextStyleRange>()
        var segmentStart: Int? = null
        sourceIndices.forEachIndexed { index, sourceIndex ->
            val covered = sourceIndex != null && sourceIndex >= range.start && sourceIndex < range.end
            if (covered && segmentStart == null) {
                segmentStart = index
            } else if (!covered && segmentStart != null) {
                remapped += range.copy(start = segmentStart, end = index)
                segmentStart = null
            }
        }
        segmentStart?.let { remapped += range.copy(start = it, end = sourceIndices.size) }
        remapped
    }.filter { it.start < it.end }
