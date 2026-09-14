package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.constrainHeight
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.TextBlockIndex

/** 滑动时冻结选择节点，新进入视口的块直接绘制；停稳后再补齐原生文字选择。 */
@Composable
internal fun ReaderDrawnTextBlockLayout(
    index: TextBlockIndex,
    viewport: ReaderTextViewport,
    isScrolling: () -> Boolean,
    drawBlock: DrawScope.(Int) -> Unit,
    content: @Composable (Int) -> Unit
) {
    var window by remember(index, viewport) { mutableStateOf<ReaderTextViewport?>(null) }
    var selectable by remember(index) { mutableStateOf(IntRange.EMPTY) }
    LaunchedEffect(index, viewport, isScrolling) {
        snapshotFlow { if (isScrolling()) null else window }.collect { visible ->
            if (visible == null) return@collect
            // 只为可见正文补齐选择；保留附近已有节点，不因停稳而预先挂载屏外正文。
            selectable = index.selectableRange(selectable, visible.top, visible.bottom)
        }
    }
    // 像素级位置只供绘制和停稳监听读取，不驱动组合或测量。
    val range = selectable
    Layout(
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned {
                val origin = it.positionInWindow().y
                window = ReaderTextViewport(viewport.top - origin, viewport.bottom - origin)
            }
            .drawBehind {
                val visible = window ?: return@drawBehind
                for (block in index.visibleRange(visible.top, visible.bottom)) {
                    // 选择节点自身负责绘制，不能与直接绘制叠加导致字形变粗。
                    if (block !in range) {
                        translate(top = index.top(block).toFloat()) { drawBlock(block) }
                    }
                }
            },
        content = {
            for (block in range) key(block) { content(block) }
        }
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        layout(constraints.maxWidth, constraints.constrainHeight(index.height)) {
            placeables.forEachIndexed { offset, placeable ->
                placeable.placeRelative(0, index.top(range.first + offset))
            }
        }
    }
}
