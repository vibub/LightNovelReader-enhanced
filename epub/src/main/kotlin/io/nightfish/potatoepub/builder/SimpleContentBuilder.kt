package io.nightfish.potatoepub.builder

import io.nightfish.potatoepub.xml.Attribute
import io.nightfish.potatoepub.xml.XmlBuilder
import org.dom4j.Document
import org.dom4j.DocumentHelper
import org.dom4j.Element
import java.io.File

@Suppress("MemberVisibilityCanBePrivate")
class SimpleContentBuilder {
    private val _images: MutableMap<Pair<String, String>, File> = mutableMapOf()
    val images: Map<Pair<String, String>, File> get() = _images
    val document: Document = DocumentHelper.createDocument()
    val rootElement: Element = document.addElement("html", "http://www.w3.org/1999/xhtml")
    val headElement: Element = rootElement.addElement("head")
    val bodyElement: Element = rootElement.addElement("body")
    val contentElement: Element = bodyElement.addElement("div").addAttribute("id", "content")

    init {
        document.addDocType("html", "", "")
        rootElement
            .addAttribute("xmlns", "http://www.w3.org/1999/xhtml")
            .addAttribute("xmlns:epub", "http://www.idpf.org/2007/ops")
            .addAttribute("lang", "en")
            .addAttribute("xml:lang", "en")
    }

    fun title(src: String) {
        headElement.addElement("title").addText(src)
    }

    @Suppress("unused")
    fun headline(level: Int, content: String) {
        contentElement.addElement("h$level").addText(content)
    }

    fun br() {
        contentElement.addElement("br")
    }

    fun text(content: String) {
        // addText 会转义文本；过滤实际非法 XML 1.0 字符，而不是删除看似实体的合法原文。
        val result = buildString {
            content.codePoints().forEach { codePoint ->
                if (codePoint == 9 || codePoint == 10 || codePoint == 13 ||
                    codePoint in 0x20..0xD7FF || codePoint in 0xE000..0xFFFD ||
                    codePoint in 0x10000..0x10FFFF
                ) appendCodePoint(codePoint)
            }
        }
        result.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, line ->
            if (index > 0) br()
            contentElement.addText(line)
        }
    }

    /**
     * 添加图片资源，并根据文件扩展名生成默认 href。
     */
    fun image(
        image: File,
        id: String = "image_${image.hashCode()}",
        src: String = "image/$id.${image.extension.ifBlank { "jpg" }}"
    ) {
        _images[Pair(id, src)] = image
        XmlBuilder.ElementBuilder(contentElement, "div", arrayOf(Attribute("class", "div_image"))) {
            "img"(
                "border" to 0,
                "class" to "image_content",
                "src" to src
            )
        }
    }

    fun build(): Document {
        return document
    }
}