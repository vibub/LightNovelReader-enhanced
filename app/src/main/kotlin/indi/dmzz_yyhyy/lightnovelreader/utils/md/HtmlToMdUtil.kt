package indi.dmzz_yyhyy.lightnovelreader.utils.md

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

object HtmlToMdUtil {
    fun convertHtml(html: String): String {
        val dirtyDoc = Jsoup.parse(html)
        val safelist = Safelist.relaxed().addTags("hr").addAttributes("ol", "start")
        val doc = Cleaner(safelist).clean(dirtyDoc)
        val content = renderChildren(doc.body()).trim('\n', '\r', ' ')
        val title = dirtyDoc.title().trim()
        return if (title.isEmpty()) content else "# ${escapeText(title)}\n\n$content"
    }

    private fun renderChildren(element: Element): String = buildString {
        for (child in element.childNodes()) {
            if (child is Element && isBlock(child)) {
                val content = renderBlock(child)
                if (content.isNotBlank()) {
                    while (isNotEmpty() && last() == ' ') setLength(length - 1)
                    if (isNotEmpty()) {
                        if (last() != '\n') append('\n')
                        if (length < 2 || this[length - 2] != '\n') append('\n')
                    }
                    append(content.trimEnd('\n'))
                    append("\n\n")
                }
            } else {
                val content = renderInline(child)
                // 忽略 HTML 美化排版在块级元素之间引入的空白，保留行内元素之间的空格。
                if (content.isNotBlank() || (isNotEmpty() && last() != '\n')) {
                    append(content)
                }
            }
        }
    }.trimEnd('\n', '\r', ' ')

    private fun isBlock(element: Element): Boolean = element.tagName() in setOf(
        "div", "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
        "ul", "ol", "blockquote", "hr", "table", "thead", "tbody", "tfoot", "tr"
    )

    private fun renderBlock(element: Element): String = when (element.tagName()) {
        "h1", "h2", "h3", "h4", "h5", "h6" ->
            "${"#".repeat(element.tagName().last().digitToInt())} ${renderChildren(element)}"
        "pre" -> {
            // 代码块必须使用原始文本，不能折叠换行或删除每行缩进。
            val text = element.wholeText().removeSuffix("\n")
            val fence = "`".repeat(maxOf(3, longestBacktickRun(text) + 1))
            "$fence\n$text\n$fence"
        }
        "ul", "ol" -> renderList(element)
        "blockquote" -> renderChildren(element).lines().joinToString("\n") {
            if (it.isEmpty()) ">" else "> $it"
        }
        "hr" -> "---"
        "tr" -> element.children().joinToString(" | ") { renderChildren(it) }
        else -> renderChildren(element)
    }

    private fun renderList(element: Element): String {
        val ordered = element.tagName() == "ol"
        var number = element.attr("start").toIntOrNull() ?: 1
        return element.children().filter { it.tagName() == "li" }.joinToString("\n") { item ->
            val marker = if (ordered) "${number++}. " else "* "
            val content = renderChildren(item).trimStart('\n', '\r', ' ')
            val lines = content.lines()
            buildString {
                append(marker)
                append(lines.first())
                for (line in lines.drop(1)) {
                    append('\n')
                    if (line.isNotEmpty()) {
                        append(" ".repeat(marker.length))
                        append(line)
                    }
                }
            }
        }
    }

    private fun renderInline(node: Node): String = when (node) {
        is TextNode -> escapeText(node.text())
        is Element -> when (node.tagName()) {
            "code" -> {
                val text = node.wholeText().replace('\n', ' ').replace('\r', ' ')
                if (text.isEmpty()) "" else {
                    val fence = "`".repeat(longestBacktickRun(text) + 1)
                    val padding = text.startsWith('`') || text.endsWith('`') ||
                        (text.startsWith(' ') && text.endsWith(' ') && text.isNotBlank())
                    if (padding) "$fence $text $fence" else "$fence$text$fence"
                }
            }
            "strong", "b" -> "**${renderChildren(node)}**"
            "em", "i" -> "*${renderChildren(node)}*"
            "br" -> "  \n"
            "a" -> {
                val label = renderChildren(node)
                val href = node.attr("href")
                if (href.isEmpty()) label else "[$label](<${escapeDestination(href)}>${linkTitle(node)})"
            }
            "img" -> "![${escapeText(node.attr("alt"))}](<${escapeDestination(node.attr("src"))}>${linkTitle(node)})"
            else -> renderChildren(node)
        }
        else -> ""
    }

    private fun longestBacktickRun(text: String): Int =
        Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0

    private fun escapeText(text: String): String = buildString {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\\', '`', '*', '_', '{', '}', '[', ']', '#', '+', '-', '.', '!', '|' -> {
                    append('\\')
                    append(char)
                }
                else -> append(char)
            }
        }
    }

    private fun escapeDestination(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("\\", "%5C")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

    private fun linkTitle(element: Element): String = element.attr("title")
        .takeIf { it.isNotEmpty() }
        ?.let { " \"${it.replace("&", "&amp;").replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        .orEmpty()
}
