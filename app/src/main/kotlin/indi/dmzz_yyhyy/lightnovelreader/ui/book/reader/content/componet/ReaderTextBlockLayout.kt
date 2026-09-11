package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.constrainHeight
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.TextBlockIndex

@Immutable
internal data class ReaderTextViewport(val top: Float, val bottom: Float) {
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal val LocalReaderTextViewport = compositionLocalOf<ReaderTextViewport?> { null }

/** 只组合窗口附近的文本块，但始终报告整段高度，不改变章节滚动进度。 */
@Composable
internal fun ReaderTextBlockLayout(
    index: TextBlockIndex,
    content: @Composable (Int) -> Unit
) {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var accessibilityEnabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isEnabled == true)
    }
    DisposableEffect(accessibilityManager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { accessibilityEnabled = it }
        accessibilityManager?.addAccessibilityStateChangeListener(listener)
        onDispose { accessibilityManager?.removeAccessibilityStateChangeListener(listener) }
    }
    // 无障碍阅读保留完整语义树；翻页模式及其他调用者未提供窗口时也不裁减节点。
    val viewport = LocalReaderTextViewport.current.takeUnless { accessibilityEnabled }
    var visible by remember(index, viewport) {
        mutableStateOf(
            if (viewport == null) 0 until index.size
            else index.visibleRange(0f, viewport.height, viewport.height)
        )
    }
    val positionModifier = if (viewport == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow().y
        // 上下各预留一屏，避免普通拖动在块边界露出空白。仅跨块时触发重组。
        val next = index.visibleRange(viewport.top - origin, viewport.bottom - origin, viewport.height)
        if (next != visible) visible = next
    }
    val range = visible
    Layout(
        modifier = Modifier.fillMaxWidth().then(positionModifier),
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
