package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

internal data class RubyTextRun(
    val key: String,
    val start: Int,
    val end: Int,
    val base: TextLayoutResult,
    val annotation: List<TextLayoutResult>,
    val annotationPlacement: RubyAnnotationPlacement,
    val annotationHeight: Int,
    val annotationTop: Int,
    val baseTop: Int,
    val placeholder: Placeholder
)

internal data class RubyAnnotationPlacement(val scale: Float, val offsets: List<Float>)

internal fun placeRubyAnnotation(
    widths: List<Int>,
    baseWidth: Int,
    minimumGap: Float = 0f
): RubyAnnotationPlacement {
    if (widths.isEmpty()) return RubyAnnotationPlacement(1f, emptyList())
    val inset = baseWidth * 0.05f
    val availableWidth = baseWidth - inset * 2
    val totalWidth = widths.sum() + minimumGap * (widths.size - 1)
    val scale = if (totalWidth > availableWidth && totalWidth > 0f) availableWidth / totalWidth else 1f
    val freeSpace = (availableWidth - widths.sum() * scale).coerceAtLeast(0f)
    val gap = if (widths.size > 1) freeSpace / (widths.size - 1) else 0f
    var offset = inset + if (widths.size == 1) freeSpace / 2 else 0f
    return RubyAnnotationPlacement(scale, widths.map { width ->
        offset.also { offset += width * scale + gap }
    })
}

internal fun String.rubyCharacters(): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(this@rubyCharacters) }
    return buildList {
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            add(substring(start, end))
            start = end
            end = iterator.next()
        }
    }
}

internal fun String.rubyAnnotationUnits(): List<String> = buildList {
    val word = StringBuilder()
    fun flushWord() {
        if (word.isNotEmpty()) {
            add(word.toString())
            word.clear()
        }
    }
    rubyCharacters().forEach { character ->
        val codePoint = character.codePointAt(0)
        when {
            Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN ||
                Character.isDigit(codePoint) || character in listOf("-", "'", "’") -> word.append(character)
            else -> {
                flushWord()
                if (!character.all { it.isWhitespace() }) add(character)
            }
        }
    }
    flushWord()
}

internal data class RubyTextLine(
    val start: Int,
    val end: Int,
    val text: AnnotatedString,
    val layout: TextLayoutResult,
    val topPadding: Int,
    val height: Int,
    val runs: List<RubyTextRun>,
    val extraAbove: Int = 0,
    val reclaimedHeight: Int = 0
)

internal data class RubyTextLayout(
    val text: AnnotatedString,
    val runs: List<RubyTextRun>,
    val lines: List<RubyTextLine> = emptyList()
) {
    val placeholders get() = runs.map { AnnotatedString.Range(it.placeholder, it.start, it.end) }
}

internal fun measureRubyText(
    text: AnnotatedString,
    ranges: List<SimpleTextStyleRange>,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    maxWidth: Int
): RubyTextLayout {
    val runs = mutableListOf<RubyTextRun>()
    val baseStyle = style.copy(lineHeight = TextUnit.Unspecified)
    val annotationStyle = baseStyle.copy(fontSize = style.fontSize * 0.6f, letterSpacing = 0.sp)
    val minimumGap by lazy {
        measurer.measure(AnnotatedString(" "), annotationStyle, softWrap = false).size.width.toFloat()
    }

    fun addRun(range: SimpleTextStyleRange, start: Int, end: Int) {
        if (start >= end) return
        val base = measurer.measure(text.subSequence(start, end), baseStyle, softWrap = false)
        val width = base.size.width.coerceAtLeast(1)
        if (width > maxWidth && end - start > 1) {
            var middle = (start + end) / 2
            if (Character.isLowSurrogate(text[middle])) middle++
            if (middle < end) {
                addRun(range, start, middle)
                addRun(range, middle, end)
                return
            }
        }
        val annotation = range.rubySubstring(start, end).orEmpty().rubyAnnotationUnits().map { character ->
            measurer.measure(AnnotatedString(character), annotationStyle, softWrap = false)
        }
        val placement = placeRubyAnnotation(annotation.map { it.size.width }, width, minimumGap)
        val annotationHeight = ceil((annotation.maxOfOrNull { it.size.height } ?: 0) * placement.scale).toInt()
        val topGap = if (annotation.isEmpty()) 0 else with(density) { ceil(style.fontSize.toPx() * 0.1f).toInt() }
        val baseGap = if (annotation.isEmpty()) 0 else with(density) { ceil(style.fontSize.toPx() * 0.08f).toInt() }
        val annotationBottomInset = annotation.minOfOrNull {
            (it.size.height - it.rubyInkBounds().bottom).coerceAtLeast(0f) * placement.scale
        } ?: 0f
        // 布局框之间可以重叠，但可见字形之间始终保留间隙；不再累加字体自身的上下留白。
        val baseTop = rubyBaseTop(
            topGap, annotationHeight, baseGap, annotationBottomInset, base.rubyInkBounds().top
        )
        val height = baseTop + base.size.height
        runs += RubyTextRun(
            key = "ruby:$start:$end",
            start = start,
            end = end,
            base = base,
            annotation = annotation,
            annotationPlacement = placement,
            annotationHeight = annotationHeight,
            annotationTop = topGap,
            baseTop = baseTop,
            placeholder = with(density) {
                Placeholder(
                    width = width.coerceAtMost(maxWidth).coerceAtLeast(1).toSp(),
                    height = height.toSp(),
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom
                )
            }
        )
    }

    var previousEnd = 0
    ranges.filter { !it.rubyText.isNullOrBlank() }.sortedBy { it.start }.forEach { range ->
        if (range.start >= previousEnd && range.end <= text.length && range.start < range.end &&
            '\n' !in text.text.substring(range.start, range.end)
        ) {
            addRun(range, range.start, range.end)
            previousEnd = range.end
        }
    }
    if (runs.isEmpty()) return RubyTextLayout(text, emptyList())
    val inlineText = buildAnnotatedString {
        var offset = 0
        runs.forEach { run ->
            append(text.subSequence(offset, run.start))
            appendInlineContent(run.key, text.text.substring(run.start, run.end))
            offset = run.end
        }
        append(text.subSequence(offset, text.length))
    }
    val horizontalLayout = measurer.measure(
        text = inlineText,
        style = style,
        placeholders = runs.map { run ->
            AnnotatedString.Range(
                Placeholder(
                    width = run.placeholder.width,
                    height = with(density) { run.base.size.height.toSp() },
                    placeholderVerticalAlign = run.placeholder.placeholderVerticalAlign
                ), run.start, run.end
            )
        },
        constraints = Constraints(maxWidth = maxWidth)
    )
    val runsByLine = runs.groupBy { horizontalLayout.getLineForOffset(it.start) }
    val lines = (0 until horizontalLayout.lineCount).map { line ->
        val start = horizontalLayout.getLineStart(line)
        val end = horizontalLayout.getLineEnd(line)
        val displayEnd = if (end > start && inlineText[end - 1] == '\n') end - 1 else end
        val lineText = inlineText.subSequence(start, displayEnd)
        val lineRuns = runsByLine[line].orEmpty()
        val originalHeight = ceil(horizontalLayout.getLineBottom(line) - horizontalLayout.getLineTop(line)).toInt()
        val measured = measurer.measure(
            text = lineText,
            style = if (lineRuns.isEmpty()) style else baseStyle,
            placeholders = lineRuns.map {
                AnnotatedString.Range(it.placeholder, it.start - start, it.end - start)
            },
            softWrap = false,
            constraints = Constraints(maxWidth = maxWidth)
        )
        val originalBaseline = horizontalLayout.getLineBaseline(line) - horizontalLayout.getLineTop(line)
        val topPadding = ceil(originalBaseline - measured.firstBaseline).toInt().coerceAtLeast(0)
        val extraAbove = if (lineRuns.isEmpty()) 0
            else ceil(topPadding + measured.firstBaseline - originalBaseline).toInt().coerceAtLeast(0)
        // 保留下侧原有空间；只有注释把正文基线向下顶开的部分才能抵扣前面的空白。
        val height = if (lineText.isEmpty() || lineRuns.isEmpty()) originalHeight
            else maxOf(originalHeight + extraAbove, topPadding + measured.size.height)
        RubyTextLine(start, end, lineText, measured, topPadding, height, lineRuns, extraAbove)
    }
    val reclaimed = reusableRubySpacing(lines.map { RubyLineSpace(it.height, it.text.isBlank(), it.extraAbove) })
    return RubyTextLayout(inlineText, runs, lines.mapIndexed { index, line ->
        line.copy(height = line.height - reclaimed[index], reclaimedHeight = reclaimed[index])
    })
}

internal fun RubyTextLayout.pageRanges(maxHeight: Int): List<IntRange> = rubyPageRanges(
    lines.map { RubyPageLine(it.start, it.end, it.height, it.text.isBlank(), it.reclaimedHeight) },
    maxHeight
)

internal fun SimpleTextStyleRange.rubySubstring(start: Int, end: Int): String? {
    val annotation = rubyText ?: return null
    if (this.end <= this.start) return null
    val count = annotation.codePointCount(0, annotation.length)
    val from = ((start - this.start).toLong() * count / (this.end - this.start)).toInt().coerceIn(0, count)
    val to = ((end - this.start).toLong() * count / (this.end - this.start)).toInt().coerceIn(from, count)
    return annotation.substring(annotation.offsetByCodePoints(0, from), annotation.offsetByCodePoints(0, to))
}
