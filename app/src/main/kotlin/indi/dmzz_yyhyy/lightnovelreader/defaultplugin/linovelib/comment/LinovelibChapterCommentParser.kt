package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

object LinovelibChapterCommentParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val quotePrefixRegex = Regex(
        pattern = "^[＠@]\\s*([^：:]+?)\\s*[：:]\\s*(.*)$",
        options = setOf(RegexOption.DOT_MATCHES_ALL)
    )

    fun parsePage(raw: String, requestedPageIndex: Int): LinovelibCommentPage {
        val root = runCatching {
            json.parseToJsonElement(raw).jsonObject
        }.getOrElse {
            throw LinovelibCommentProtocolException("Linovelib 评论响应不是有效 JSON", it)
        }
        if (root.stringValue("err_msg") != SUCCESS) {
            val info = root.stringValue("info").ifBlank { "Linovelib 评论接口返回失败状态" }
            throw LinovelibCommentProtocolException(info)
        }

        val pageIndex = root.intValue("pageIndex")
            ?.takeIf { it > 0 }
            ?: requestedPageIndex.coerceAtLeast(1)
        val pageTotal = root.intValue("pageTotal")
            ?.coerceAtLeast(1)
            ?: 1
        val comments = root.arrayValue("data")
            .mapIndexedNotNull { index, element ->
                val comment = element as? JsonObject ?: return@mapIndexedNotNull null
                comment.parseComment(pageIndex, index)
            }

        return LinovelibCommentPage(
            comments = comments,
            totalCount = root.intValue("total")?.coerceAtLeast(0) ?: comments.size,
            participantCount = root.intValue("onclick")?.coerceAtLeast(0) ?: 0,
            pageIndex = pageIndex,
            pageTotal = pageTotal,
            hasMore = root.booleanValue("hasmore") ?: (pageIndex < pageTotal)
        )
    }

    private fun JsonObject.parseComment(pageIndex: Int, itemIndex: Int): LinovelibChapterComment? {
        val parsedText = parseCommentText(stringValue("saytext"))
        if (parsedText.body.isBlank()) return null

        val username = stringValue("plusername")
        val publishedAt = stringValue("formattime")
        val id = stringValue("plid").ifBlank {
            val identity = "$pageIndex|$itemIndex|$username|$publishedAt|${parsedText.body}"
            "local-${identity.hashCode().toUInt().toString(16)}"
        }
        return LinovelibChapterComment(
            id = id,
            username = username,
            avatarUrl = LinovelibJsoup.normalizeUrl(stringValue("userpic")),
            userProfileUrl = LinovelibJsoup.normalizeUrl(stringValue("userinfo")),
            publishedAt = publishedAt,
            honor = stringValue("honor"),
            body = parsedText.body,
            quotedReplies = parsedText.quotes,
            likeCount = intValue("zcnum")?.coerceAtLeast(0) ?: 0,
            dislikeCount = intValue("fdnum")?.coerceAtLeast(0) ?: 0,
            isSpoiler = booleanValue("ispoiler") ?: false
        )
    }

    private fun parseCommentText(raw: String): ParsedCommentText {
        if (raw.isBlank()) return ParsedCommentText()
        val document = Jsoup.parseBodyFragment(raw, LinovelibConstants.BASE_URL)
        document.select("script, style, iframe, object, embed, link, meta").remove()
        val quotes = document.select(".ecomment .ecommentauthor")
            .mapNotNull { element ->
                val text = element.textWithLineBreaks()
                if (text.isBlank()) return@mapNotNull null
                val match = quotePrefixRegex.matchEntire(text)
                if (match == null) {
                    LinovelibCommentQuote(username = "", body = text)
                } else {
                    LinovelibCommentQuote(
                        username = match.groupValues[1].trim(),
                        body = match.groupValues[2].cleanMultilineText()
                    )
                }
            }
        document.select(".ecomment").remove()
        return ParsedCommentText(
            body = document.body().textWithLineBreaks(),
            quotes = quotes
        )
    }

    private fun Element.textWithLineBreaks(): String = clone().run {
        select("br").forEach { br ->
            br.before(TextNode("\n"))
            br.remove()
        }
        wholeText().cleanMultilineText()
    }

    private fun String.cleanMultilineText(): String {
        val lines = replace(' ', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .map { it.trim() }
            .toMutableList()
        while (lines.firstOrNull()?.isBlank() == true) lines.removeAt(0)
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun JsonObject.stringValue(key: String): String = this[key].stringValue().orEmpty().trim()

    private fun JsonObject.intValue(key: String): Int? = this[key]
        .stringValue()
        ?.trim()
        ?.toIntOrNull()

    private fun JsonObject.booleanValue(key: String): Boolean? = when (this[key].stringValue()?.trim()?.lowercase()) {
        "1", "true" -> true
        "0", "false" -> false
        else -> null
    }

    private fun JsonObject.arrayValue(key: String): JsonArray = runCatching {
        this[key]?.jsonArray
    }.getOrNull() ?: JsonArray(emptyList())

    private fun JsonElement?.stringValue(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content
        else -> null
    }

    private data class ParsedCommentText(
        val body: String = "",
        val quotes: List<LinovelibCommentQuote> = emptyList()
    )

    private const val SUCCESS = "success"
}
