package io.nightfish.lightnovelreader.api.content.component

import android.content.Context
import io.nightfish.lightnovelreader.api.identifier.ofAppId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.dom4j.DocumentHelper
import org.dom4j.Element

/**
 * 简单文本组件数据
 * 用于在章节内容中嵌入文本段落，可选携带少量行内富文本样式范围
 *
 * @param text 文本内容，支持多行（\n 分隔）
 * @param styleRanges 应用于 [text] 中指定字符范围的行内样式列表
 *
 * @since Api 2
 */
@Serializable
data class SimpleTextComponentData(
    val text: String,
    val styleRanges: List<SimpleTextStyleRange> = emptyList()
): AbstractContentComponentData() {
    override val id = Companion.id
    override fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    override fun toHtmlElement(context: Context): Element = DocumentHelper.createElement("div").apply {
        appendStyledText(text, styleRanges)
        addElement("br")
    }

    /** [SimpleTextComponentData] 工厂方法和常量集合 */
    companion object {
        /** 简单文本组件的唯一标识 */
        val id = "simple_text".ofAppId()
        /** 默认 JSON 序列化器 */
        val jsonSerializer = object: ComponentDataJsonElementSerializer<SimpleTextComponentData> {
            override fun toJsonElement(data: SimpleTextComponentData): JsonElement = Json.encodeToJsonElement(data)
            override fun fromJsonElement(json: JsonElement): SimpleTextComponentData = Json.decodeFromJsonElement(json)
        }
    }
}

/**
 * 简单文本的行内样式范围
 *
 * @param start 样式起始字符下标，包含该位置
 * @param end 样式结束字符下标，不包含该位置
 * @param fontWeight 字体粗细，使用 Compose FontWeight 的数值约定，例如 700 表示粗体
 * @param italic 是否使用斜体
 * @param underline 是否添加下划线
 * @param strikethrough 是否添加删除线
 */
@Serializable
data class SimpleTextStyleRange(
    val start: Int,
    val end: Int,
    val fontWeight: Int? = null,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false
)

private fun Element.appendStyledText(
    text: String,
    styleRanges: List<SimpleTextStyleRange>
) {
    var index = 0
    while (index < text.length) {
        if (text[index] == '\n') {
            addElement("br")
            index++
            continue
        }
        val nextNewLine = text.indexOf('\n', startIndex = index).takeIf { it >= 0 } ?: text.length
        val nextStyleBoundary = styleRanges
            .flatMap { listOf(it.start, it.end) }
            .map { it.coerceIn(0, text.length) }
            .filter { it > index }
            .minOrNull() ?: text.length
        val nextIndex = minOf(nextNewLine, nextStyleBoundary)
        val segment = text.substring(index, nextIndex)
        val css = styleRanges
            .filter { index >= it.start && index < it.end }
            .toCssStyle()
        if (css.isBlank()) {
            addText(segment)
        } else {
            addElement("span")
                .addAttribute("style", css)
                .addText(segment)
        }
        index = nextIndex
    }
}

private fun List<SimpleTextStyleRange>.toCssStyle(): String {
    if (isEmpty()) return ""
    val fontWeight = mapNotNull { it.fontWeight }.maxOrNull()
    val decorations = buildList {
        if (this@toCssStyle.any { it.underline }) add("underline")
        if (this@toCssStyle.any { it.strikethrough }) add("line-through")
    }
    return buildList {
        fontWeight?.let { add("font-weight: $it") }
        if (this@toCssStyle.any { it.italic }) add("font-style: italic")
        if (decorations.isNotEmpty()) add("text-decoration: ${decorations.joinToString(" ")}")
    }.joinToString("; ")
}
