package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

internal object LinovelibChapterContentParser {
    const val WARNING_KEY = "linovelibParserWarning"
    const val WARNING_MESSAGE = "Linovelib 本章段落顺序还原失败，已使用网页源码顺序显示，段落可能仍然乱序。"

    sealed interface Part {
        data class Text(val text: String) : Part
        data class Image(val url: String) : Part
    }

    data class ParseResult(
        val parts: List<Part>,
        val warning: String? = null
    )

    fun parse(content: Element, chapterId: String? = null, imageUrl: (Element) -> String): ParseResult {
        val orderedNodes = content.orderedChildNodes(chapterId)
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

    private data class OrderedNodes(
        val nodes: List<Node>,
        val warning: String? = null
    )

    private fun Element.orderedChildNodes(chapterId: String?): OrderedNodes {
        val nodes = childNodes()
        restoreByExplicitOrder(nodes)?.let { return it }
        restoreByLinovelibSeededOrder(nodes, chapterId)?.let { return it }
        return OrderedNodes(nodes)
    }

    private fun restoreByExplicitOrder(nodes: List<Node>): OrderedNodes? {
        val sortableElements = nodes
            .filterIsInstance<Element>()
            .filterNot { it.`is`("script, style, noscript") }
            .filter { it.isParagraphLikeForOrdering() }
        if (sortableElements.size < 2) return null

        val keyed = sortableElements.map { element -> element to element.explicitOrderKey() }
        if (keyed.none { it.second != null }) return null
        if (keyed.any { it.second == null }) return OrderedNodes(nodes, WARNING_MESSAGE)

        val keys = keyed.mapNotNull { it.second }
        if (keys.distinct().size != keys.size) return OrderedNodes(nodes, WARNING_MESSAGE)

        val sortedElements = keyed
            .sortedWith(compareBy<Pair<Element, Int?>> { it.second ?: Int.MAX_VALUE })
            .map { it.first }
        val beforeTexts = sortableElements.map { it.cleanOwnTextForCompare() }.sorted()
        val afterTexts = sortedElements.map { it.cleanOwnTextForCompare() }.sorted()
        if (beforeTexts != afterTexts) return OrderedNodes(nodes, WARNING_MESSAGE)

        val queue = ArrayDeque(sortedElements)
        val sortedNodes = nodes.map { node ->
            if (node is Element && node.isParagraphLikeForOrdering() && !node.`is`("script, style, noscript")) {
                queue.removeFirst()
            } else {
                node
            }
        }
        return OrderedNodes(sortedNodes)
    }

    private fun restoreByLinovelibSeededOrder(nodes: List<Node>, chapterId: String?): OrderedNodes? {
        val paragraphs = nodes.mapNotNull { node ->
            val element = node as? Element ?: return@mapNotNull null
            if (!element.`is`("p") || element.html().replace(Regex("\\s+"), "").isEmpty()) {
                return@mapNotNull null
            }
            element
        }
        if (paragraphs.size <= LINOVELIB_STABLE_PARAGRAPH_COUNT) return null
        val normalizedChapterId = chapterId
            ?.substringBefore('_')
            ?.toLongOrNull()
            ?: return OrderedNodes(nodes, WARNING_MESSAGE)

        val permutation = linovelibParagraphPermutation(paragraphs.size, normalizedChapterId)
        if (permutation.distinct().size != paragraphs.size) return OrderedNodes(nodes, WARNING_MESSAGE)

        val restoredParagraphs = MutableList<Element?>(paragraphs.size) { null }
        permutation.forEachIndexed { sourceIndex, targetIndex ->
            if (targetIndex !in restoredParagraphs.indices) return OrderedNodes(nodes, WARNING_MESSAGE)
            restoredParagraphs[targetIndex] = paragraphs[sourceIndex]
        }
        if (restoredParagraphs.any { it == null }) return OrderedNodes(nodes, WARNING_MESSAGE)

        var paragraphIndex = 0
        val restoredNodes = nodes.map { node ->
            if (node is Element && node.`is`("p") && node.html().replace(Regex("\\s+"), "").isNotEmpty()) {
                restoredParagraphs[paragraphIndex++] ?: return OrderedNodes(nodes, WARNING_MESSAGE)
            } else {
                node
            }
        }
        val beforeTexts = paragraphs.map { it.cleanOwnTextForCompare() }.sorted()
        val afterTexts = restoredNodes
            .filterIsInstance<Element>()
            .filter { it.`is`("p") && it.html().replace(Regex("\\s+"), "").isNotEmpty() }
            .map { it.cleanOwnTextForCompare() }
            .sorted()
        if (beforeTexts != afterTexts) return OrderedNodes(nodes, WARNING_MESSAGE)
        return OrderedNodes(restoredNodes)
    }

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
