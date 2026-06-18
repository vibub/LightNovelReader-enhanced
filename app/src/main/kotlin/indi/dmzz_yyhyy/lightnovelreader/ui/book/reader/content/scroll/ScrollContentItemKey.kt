package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

private const val SCROLL_CONTENT_KEY_PREFIX = "scroll-content"
private const val SCROLL_CONTENT_KEY_SEPARATOR = '|'

internal enum class ScrollContentItemType {
    Header,
    Component,
    Footer
}

internal data class ParsedScrollContentItemKey(
    val chapterId: String,
    val type: ScrollContentItemType,
    val index: Int = -1
)

internal fun scrollContentItemKey(
    chapterId: String,
    type: ScrollContentItemType,
    index: Int = -1
): String = listOf(
    SCROLL_CONTENT_KEY_PREFIX,
    chapterId,
    type.name,
    index.toString()
).joinToString(SCROLL_CONTENT_KEY_SEPARATOR.toString())

internal fun Any?.scrollContentItemKeyOrNull(): ParsedScrollContentItemKey? {
    val value = this as? String ?: return null
    val payload = value.removePrefix("$SCROLL_CONTENT_KEY_PREFIX$SCROLL_CONTENT_KEY_SEPARATOR")
    if (payload == value) return null
    val indexSeparator = payload.lastIndexOf(SCROLL_CONTENT_KEY_SEPARATOR)
    if (indexSeparator < 0) return null
    val typeSeparator = payload.lastIndexOf(SCROLL_CONTENT_KEY_SEPARATOR, startIndex = indexSeparator - 1)
    if (typeSeparator < 0) return null
    val type = runCatching {
        ScrollContentItemType.valueOf(payload.substring(typeSeparator + 1, indexSeparator))
    }.getOrNull() ?: return null
    return ParsedScrollContentItemKey(
        chapterId = payload.substring(0, typeSeparator),
        type = type,
        index = payload.substring(indexSeparator + 1).toIntOrNull() ?: -1
    )
}

internal fun Any?.scrollContentChapterId(): String? =
    scrollContentItemKeyOrNull()?.chapterId ?: this as? String
