package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal object LinovelibChapterContentParser {
    const val WARNING_KEY = "linovelibParserWarning"
    const val WARNING_MESSAGE = "Linovelib 本章段落顺序还原失败，已停止使用网页源码乱序正文。请刷新章节或反馈该章节 ID。"

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Image(val url: String) : Part
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
            val pendingText = StringBuilder()

            fun flushText() {
                val text = pendingText.toString().cleanText()
                pendingText.clear()
                if (text.isNotBlank() && !text.isNoiseText()) add(Part.Text(text))
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
                            node.childNodes().forEach(::appendNode)
                            if (node.isBlockTextElement()) {
                                pendingText.append('\n')
                                flushText()
                            }
                        }
                    }
                }
            }

            orderedNodes.nodes.forEach(::appendNode)
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
            .filter { it.isParagraphLikeForOrdering() }
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
            if (node is Element && node.isParagraphLikeForOrdering() && !node.`is`("script, style, noscript")) {
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

        val directParagraphs = nodes
            .filterIsInstance<Element>()
            .filter { it.isNonEmptyParagraph() }
        val allParagraphsAreDirect = directParagraphs.size == paragraphs.size &&
            directParagraphs.zip(paragraphs).all { (direct, paragraph) -> direct === paragraph }

        if (!allParagraphsAreDirect) {
            return RestoreResult.Restored(restored.map { it.clone() })
        }

        var paragraphIndex = 0
        val restoredNodes = nodes.map { node ->
            if (node is Element && node.isNonEmptyParagraph()) {
                restored[paragraphIndex++]
            } else {
                node
            }
        }
        return RestoreResult.Restored(restoredNodes)
    }

    private fun Element.seededParagraphs(): List<Element> = select("p")
        .filter { it.isNonEmptyParagraph() }

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
        val pendingText = StringBuilder()

        fun flushText() {
            val text = pendingText.toString().cleanText()
            pendingText.clear()
            if (text.isNotBlank()) merged.add(Part.Text(text))
        }

        forEach { part ->
            when (part) {
                is Part.Text -> {
                    if (pendingText.isNotBlank()) pendingText.append("\n\n")
                    pendingText.append(part.text)
                }
                is Part.Image -> {
                    flushText()
                    merged.add(part)
                }
            }
        }
        flushText()
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

    private fun Element.isBlockTextElement(): Boolean = `is`("p, div, section, article, center, li, blockquote")

    private fun Element.cleanOwnTextForCompare(): String = text().cleanText()

    private fun String.cleanText(): String = replace(' ', ' ')
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun String.isNoiseText(): Boolean = contains("最新网址") || contains("请收藏") || contains("本章未完")

    private const val LINOVELIB_STABLE_PARAGRAPH_COUNT = 20
    private const val LINOVELIB_SEED_MULTIPLIER = 126L
    private const val LINOVELIB_SEED_OFFSET = 232L
    private const val LINOVELIB_SHUFFLE_MULTIPLIER = 9302L
    private const val LINOVELIB_SHUFFLE_INCREMENT = 49397L
    private const val LINOVELIB_SHUFFLE_MODULUS = 233280L

    private val ORDER_STYLE_REGEX = Regex("(?:^|;)\\s*order\\s*:\\s*(-?\\d+)", RegexOption.IGNORE_CASE)
    private val ORDER_ATTRS = listOf(
        "data-order",
        "data-index",
        "data-idx",
        "data-id",
        "data-sort",
        "data-seq"
    )
}
