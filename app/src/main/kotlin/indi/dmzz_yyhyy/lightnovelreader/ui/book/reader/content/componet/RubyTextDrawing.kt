package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.drawText
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.RubyTextLine
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.RubyTextRun
import kotlin.math.floor
import kotlin.math.roundToInt

/** 直接复用排版结果；与 BasicText 一样，对占位符宽度向下取整、位置四舍五入。 */
internal fun DrawScope.drawRubyTextBlock(first: RubyTextLine, color: Color) {
    if (first.text.isBlank()) return
    translate(top = first.topPadding.toFloat()) {
        drawText(first.layout, color = color)
        first.runs.forEachIndexed { index, run ->
            val rect = first.layout.placeholderRects[index] ?: return@forEachIndexed
            translate(rect.left.roundToInt().toFloat(), rect.top.roundToInt().toFloat()) {
                drawRubyTextRun(run, color, floor(rect.width))
            }
        }
    }
}

/** 内联文本与直接绘制共用同一套注释缩放、分布和基线计算。 */
internal fun DrawScope.drawRubyTextRun(run: RubyTextRun, color: Color, width: Float) {
    if (width <= 0f) return
    val naturalWidth = run.base.size.width.coerceAtLeast(1).toFloat()
    val scaleX = (width / naturalWidth).coerceAtMost(1f)
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
        drawText(run.base, color = color, topLeft = Offset(0f, run.baseTop.toFloat()))
    }
}
