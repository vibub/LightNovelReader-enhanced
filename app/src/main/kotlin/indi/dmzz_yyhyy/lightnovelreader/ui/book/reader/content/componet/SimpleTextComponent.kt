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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.TextBlockIndex
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.measureRubyText
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.renderBlocks
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList

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
    val localeList = LocalTextLocaleList.current
    val style = MaterialTheme.typography.bodyMedium.copy(
        localeList = localeList,
        fontWeight = fontWeight,
        fontSize = fontSize,
        fontFamily = fontFamily,
        color = color,
        textAlign = TextAlign.Start,
        lineHeight = (fontSize.value + fontLineHeight.value).sp
    )

    SelectionContainer {
        if (styleRanges.none { !it.rubyText.isNullOrBlank() }) {
            Text(modifier = modifier.fillMaxWidth(), text = text, style = style)
        } else {
            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val width = constraints.maxWidth
                val layout = remember(text, styleRanges, style, measurer, density, width) {
                    measureRubyText(text, styleRanges, style, measurer, density, width)
                }
                val inlineContent = remember(layout, color) {
                    layout.runs.associate { run ->
                        run.key to InlineTextContent(run.placeholder) {
                            Canvas(Modifier.fillMaxSize()) {
                                if (size.width <= 0f) return@Canvas
                                val naturalWidth = run.base.size.width.coerceAtLeast(1).toFloat()
                                val scaleX = (size.width / naturalWidth).coerceAtMost(1f)
                                scale(scaleX, 1f, pivot = Offset.Zero) {
                                    val annotationScale = run.annotationPlacement.scale
                                    scale(annotationScale, annotationScale, pivot = Offset.Zero) {
                                        run.annotation.forEachIndexed { index, character ->
                                            drawText(
                                                character, color = color,
                                                topLeft = Offset(
                                                    run.annotationPlacement.offsets[index] / annotationScale,
                                                    run.annotationTop / annotationScale +
                                                        (run.annotationHeight / annotationScale - character.size.height)
                                                            .coerceAtLeast(0f)
                                                )
                                            )
                                        }
                                    }
                                    drawText(
                                        run.base, color = color,
                                        topLeft = Offset(0f, run.baseTop.toFloat())
                                    )
                                }
                            }
                        }
                    }
                }
                val blocks = remember(layout) { layout.renderBlocks() }
                val heights = remember(blocks) { blocks.map { block -> block.sumOf { it.height } } }
                val index = remember(heights) { TextBlockIndex(heights) }
                ReaderTextBlockLayout(index) { blockIndex ->
                    val first = blocks[blockIndex].first()
                    Box(Modifier.fillMaxWidth().height(with(density) { heights[blockIndex].toDp() })) {
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
