package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange

@Composable
fun SimpleTextComponentContent(
    modifier: Modifier,
    text: AnnotatedString,
    fontSize: TextUnit,
    fontLineHeight: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    color: Color,
    styleRanges: List<SimpleTextStyleRange> = emptyList()
) {
    val style = readerRubyTextStyle(fontSize, fontLineHeight, fontWeight, fontFamily, color)

    SelectionContainer {
        if (styleRanges.none { !it.rubyText.isNullOrBlank() }) {
            Text(modifier = modifier.fillMaxWidth(), text = text, style = style)
        } else {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val environment = RubyTextEnvironment(
                    style, Density(density.density, density.fontScale),
                    LocalLayoutDirection.current, LocalFontFamilyResolver.current, constraints.maxWidth
                )
                val key = remember(text, styleRanges) { RubyTextKey(text, styleRanges) }
                val cache = LocalReaderRubyTextCache.current
                val cached = cache?.get(key, environment)
                val staleFonts = cached?.hasStaleFonts == true
                val prepared = remember(key, environment, measurer, cached, staleFonts) {
                    // 预排版未完成时仍计算精确高度，不以临时占位改变章节位置和阅读进度。
                    cached?.takeUnless { staleFonts } ?: prepareRubyText(key, environment, measurer)
                }
                SideEffect { cache?.put(key, environment, prepared) }
                val layout = prepared.layout
                val inlineContent = remember(layout, color) {
                    layout.runs.associate { run ->
                        run.key to InlineTextContent(run.placeholder) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRubyTextRun(run, color, size.width)
                            }
                        }
                    }
                }
                val blocks = prepared.blocks
                val heights = prepared.heights
                val index = prepared.index
                ReaderTextBlockLayout(
                    index = index,
                    drawBlock = { blockIndex -> drawRubyTextBlock(blocks[blockIndex].first(), color) }
                ) { blockIndex ->
                    val first = blocks[blockIndex].first()
                    Box(Modifier.fillMaxWidth().height(with(density) { heights[blockIndex].toDp() }).graphicsLayer()) {
                        if (first.text.isNotBlank()) {
                            BasicText(
                                modifier = Modifier.fillMaxWidth().offset(y = with(density) { first.topPadding.toDp() }),
                                text = first.layout.layoutInput.text,
                                style = first.layout.layoutInput.style,
                                softWrap = true,
                                inlineContent = if (first.runs.isEmpty()) emptyMap() else inlineContent
                            )
                        }
                    }
                }
            }
        }
    }
}
