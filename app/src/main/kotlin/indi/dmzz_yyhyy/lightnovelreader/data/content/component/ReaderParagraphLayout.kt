package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ReaderParagraphPadding(val before: Int, val after: Int) {
    val height: Int get() = before + after
}

internal data class ReaderParagraphSpacing(
    val before: TextUnit = 0.sp,
    val after: TextUnit = 0.sp,
    val fontSize: TextUnit = 16.sp
) {
    fun padding(density: Density, startsParagraph: Boolean, endsParagraph: Boolean) = ReaderParagraphPadding(
        if (startsParagraph) before.pixels(density, fontSize).roundToInt().coerceAtLeast(0) else 0,
        if (endsParagraph) after.pixels(density, fontSize).roundToInt().coerceAtLeast(0) else 0
    )
}

internal fun ReaderStyle.paragraphSpacing() = ReaderParagraphSpacing(
    spacingBeforeParagraph, spacingAfterParagraph, fontSize
)

/** 注释只借用段间留白，优先本段段前间距，再使用前段段后或小节留白。 */
internal fun reusableParagraphSpacing(extraAbove: Int, before: Int, previousAfter: Int): ReaderParagraphPadding {
    val needed = extraAbove.coerceAtLeast(0)
    val usedBefore = minOf(needed, before.coerceAtLeast(0))
    return ReaderParagraphPadding(usedBefore, minOf(needed - usedBefore, previousAfter.coerceAtLeast(0)))
}

private fun TextUnit.pixels(density: Density, fontSize: TextUnit): Float = when {
    isSp -> with(density) { toPx() }
    isEm -> value * with(density) { fontSize.toPx() }
    else -> 0f
}.takeIf { it.isFinite() } ?: 0f

/** 保留原文坐标；只在排版时消费站点缩进和常规段落分隔，不改写缓存正文。 */
internal data class ReaderTextParagraph(
    val start: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val end: Int,
    val blankLinesAfter: Int,
    val startsParagraph: Boolean,
    val endsParagraph: Boolean
)

private val readerParagraphSeparator = Regex("\n(?:[ \t\r　 ]*\n)+")

internal fun readerTextParagraphs(
    text: String,
    startsParagraph: Boolean = true,
    endsParagraph: Boolean = true
): List<ReaderTextParagraph> = buildList {
    var start = 0
    while (start < text.length) {
        // 站点用 NBSP 或全角空格撑起的空白行也属于分隔符，不再作为下一段正文测量。
        val match = readerParagraphSeparator.find(text, start)
        val separator = match?.range?.first ?: text.length
        val end = match?.range?.last?.plus(1) ?: text.length
        val lineBreaks = match?.value?.count { it == '\n' } ?: 0
        val isStart = start != 0 || startsParagraph
        var contentStart = start
        if (isStart) {
            while (contentStart < separator && text[contentStart] in " \t　 ") contentStart++
        }
        if (contentStart == separator) contentStart = start
        val hasText = (contentStart until separator).any { !text[it].isWhitespace() && text[it] != ' ' }
        add(ReaderTextParagraph(
            start, contentStart, separator, end,
            (lineBreaks - if (hasText) 2 else 1).coerceAtLeast(0),
            hasText && isStart, hasText && (end != text.length || endsParagraph)
        ))
        start = end
    }
}

/** 单换行是段内换行；自动折行和分页续段都不得重复首行缩进。 */
internal fun AnnotatedString.withContinuationIndent(indent: TextIndent): AnnotatedString = buildAnnotatedString {
    append(this@withContinuationIndent)
    val end = this@withContinuationIndent.text.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: length
    if (end > 0) addStyle(ParagraphStyle(textIndent = TextIndent(indent.restLine, indent.restLine)), 0, end)
}

private fun AnnotatedString.withParagraphLineBreaks(indent: TextIndent): AnnotatedString = buildAnnotatedString {
    append(this@withParagraphLineBreaks)
    var start = this@withParagraphLineBreaks.text.indexOf('\n').takeIf { it >= 0 }?.plus(1) ?: length
    while (start < length) {
        val end = this@withParagraphLineBreaks.text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: length
        addStyle(ParagraphStyle(textIndent = TextIndent(indent.restLine, indent.restLine)), start, end)
        start = end
    }
}

internal fun measureReaderText(
    text: AnnotatedString,
    ranges: List<SimpleTextStyleRange>,
    paragraphs: List<ReaderTextParagraph>,
    style: TextStyle,
    spacing: ReaderParagraphSpacing,
    measurer: TextMeasurer,
    density: Density,
    maxWidth: Int,
    checkCancelled: () -> Unit = {}
): RubyTextLayout {
    val lines = mutableListOf<RubyTextLine>()
    val runs = mutableListOf<RubyTextRun>()
    val sortedRanges = ranges.filter { !it.rubyText.isNullOrBlank() }.sortedBy { it.start }
    var firstRange = 0
    var previousGap = 0
    paragraphs.forEach { paragraph ->
        checkCancelled()
        val indent = style.textIndent ?: TextIndent.None
        val paragraphStyle = if (paragraph.startsParagraph) style else
            style.copy(textIndent = TextIndent(indent.restLine, indent.restLine))
        val paragraphText = text.subSequence(paragraph.contentStart, paragraph.contentEnd)
            .withParagraphLineBreaks(indent)
        while (firstRange < sortedRanges.size && sortedRanges[firstRange].end <= paragraph.contentStart) firstRange++
        val paragraphRanges = buildList {
            var index = firstRange
            while (index < sortedRanges.size && sortedRanges[index].start < paragraph.contentEnd) {
                val range = sortedRanges[index++]
                val start = maxOf(range.start, paragraph.contentStart)
                val end = minOf(range.end, paragraph.contentEnd)
                if (start < end) add(range.copy(
                    start = start - paragraph.contentStart,
                    end = end - paragraph.contentStart,
                    rubyText = range.rubySubstring(start, end)
                ))
            }
        }
        val ruby = measureRubyText(paragraphText, paragraphRanges, paragraphStyle, measurer, density, maxWidth, checkCancelled)
        runs += ruby.runs.map { it.copy(start = it.start + paragraph.contentStart, end = it.end + paragraph.contentStart) }
        val paragraphLines = ruby.lines
        val padding = spacing.padding(density, paragraph.startsParagraph, paragraph.endsParagraph)
        val blankHeight = ceil(style.lineHeight.pixels(density, style.fontSize)
            .takeIf { it > 0f } ?: style.fontSize.pixels(density, style.fontSize)).toInt()
        val gap = padding.after + paragraph.blankLinesAfter * blankHeight
        val credit = reusableParagraphSpacing(paragraphLines.firstOrNull()?.extraAbove ?: 0, padding.before, previousGap)
        if (credit.after > 0 && lines.isNotEmpty()) {
            val previous = lines.last()
            lines[lines.lastIndex] = previous.copy(
                height = previous.height - credit.after,
                paragraphGapReclaimed = previous.paragraphGapReclaimed + credit.after
            )
        }
        paragraphLines.forEachIndexed { index, line ->
            val before = if (index == 0) padding.before - credit.before else 0
            val after = if (index == paragraphLines.lastIndex) gap else 0
            lines += line.copy(
                start = if (index == 0) paragraph.start else paragraph.contentStart + line.start,
                end = if (index == paragraphLines.lastIndex) paragraph.end else paragraph.contentStart + line.end,
                topPadding = line.topPadding + before,
                height = line.height + before + after
            )
        }
        previousGap = if (paragraphText.isBlank()) lines.lastOrNull()?.height ?: 0 else gap
    }
    return RubyTextLayout(text, runs, lines)
}
