package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.ceil

internal data class RubyInkBounds(val top: Float, val bottom: Float)

internal fun TextLayoutResult.rubyInkBounds(): RubyInkBounds {
    val input = layoutInput
    val text = input.text
    val boundaries = (listOf(0, text.length) + text.spanStyles.flatMap { listOf(it.start, it.end) })
        .distinct().sorted()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val bounds = Rect()
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    boundaries.zipWithNext().forEach { (start, end) ->
        if (start == end) return@forEach
        val span = text.spanStyles.filter { it.start < end && it.end > start }
            .fold(input.style.toSpanStyle()) { style, range -> style.merge(range.item) }
        paint.typeface = input.fontFamilyResolver.resolve(
            span.fontFamily,
            span.fontWeight ?: FontWeight.Normal,
            span.fontStyle ?: FontStyle.Normal,
            span.fontSynthesis ?: FontSynthesis.All
        ).value as Typeface
        paint.textSize = with(input.density) { span.fontSize.toPx() }
        paint.getTextBounds(text.text, start, end, bounds)
        if (!bounds.isEmpty) {
            top = minOf(top, firstBaseline + bounds.top)
            bottom = maxOf(bottom, firstBaseline + bounds.bottom)
        }
    }
    return if (top.isFinite() && bottom.isFinite()) RubyInkBounds(top, bottom)
        else RubyInkBounds(0f, size.height.toFloat())
}

internal fun rubyBaseTop(
    annotationTop: Int,
    annotationHeight: Int,
    gap: Int,
    annotationBottomInset: Float,
    baseTopInset: Float
): Int = ceil(
    annotationTop + annotationHeight + gap - annotationBottomInset.coerceAtLeast(0f) - baseTopInset.coerceAtLeast(0f)
).toInt().coerceAtLeast(0)
