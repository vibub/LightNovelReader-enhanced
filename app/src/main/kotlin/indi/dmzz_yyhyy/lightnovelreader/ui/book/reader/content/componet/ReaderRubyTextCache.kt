package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import android.os.Trace
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.RetainedLayoutCache
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.RubyTextLayout
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.TextBlockIndex
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.measureRubyText
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.renderBlocks
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList

internal val LocalReaderRubyTextCache = compositionLocalOf<ReaderRubyTextCache?> { null }

@Composable
internal fun readerRubyTextStyle(
    fontSize: TextUnit,
    fontLineHeight: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    color: Color
): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    localeList = LocalTextLocaleList.current,
    fontWeight = fontWeight,
    fontSize = fontSize,
    fontFamily = fontFamily,
    color = color,
    textAlign = TextAlign.Start,
    lineHeight = (fontSize.value + fontLineHeight.value).sp
)

internal data class RubyTextKey(
    val text: AnnotatedString,
    val ranges: List<SimpleTextStyleRange>
)

internal data class RubyTextEnvironment(
    val style: TextStyle,
    val density: Density,
    val direction: LayoutDirection,
    val resolver: FontFamily.Resolver,
    val width: Int
) {
    // 不与主线程共享 TextMeasurer 的可变布局缓存。
    fun newMeasurer() = TextMeasurer(resolver, density, direction)
}

internal class PreparedRubyText(val layout: RubyTextLayout) {
    val blocks = layout.renderBlocks()
    val index = TextBlockIndex(blocks.map { it.height })
    private val paragraphs = (layout.lines.map { it.layout } +
        layout.runs.flatMap { listOf(it.base) + it.annotation })
        .map { it.multiParagraph }.distinct()

    val hasStaleFonts: Boolean
        get() = paragraphs.any { it.intrinsics.hasStaleResolvedFonts }
}

/** 生命周期独立于 LazyColumn 的章节组合，但不跨书籍或排版环境保留。 */
internal class ReaderRubyTextCache(val environment: RubyTextEnvironment) {
    private val values = RetainedLayoutCache<RubyTextKey, PreparedRubyText>()

    fun get(key: RubyTextKey, environment: RubyTextEnvironment): PreparedRubyText? =
        if (this.environment == environment) values[key] else null

    fun put(key: RubyTextKey, environment: RubyTextEnvironment, value: PreparedRubyText) {
        if (this.environment == environment) values.put(key, value)
    }

    fun retain(keys: Set<RubyTextKey>) = values.retain(keys)
}

internal fun prepareRubyText(
    key: RubyTextKey,
    environment: RubyTextEnvironment,
    measurer: TextMeasurer,
    checkCancelled: () -> Unit = {}
): PreparedRubyText {
    Trace.beginSection("Reader:rubyLayout")
    try {
        val layout = measureRubyText(
            key.text, key.ranges, environment.style, measurer,
            environment.density, environment.width, checkCancelled
        )
        checkCancelled()
        return PreparedRubyText(layout)
    } finally {
        Trace.endSection()
    }
}
