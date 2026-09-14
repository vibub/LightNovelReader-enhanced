package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.constrainHeight
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.TextBlockIndex
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.prefetchTextBlock
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.retainTextBlocks

@Immutable
internal data class ReaderTextViewport(val top: Float, val bottom: Float) {
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

internal val LocalReaderTextViewport = compositionLocalOf<ReaderTextViewport?> { null }
internal val LocalReaderTextScrolling = compositionLocalOf<(() -> Boolean)?> { null }

/** 只组合窗口附近的文本块，但始终报告整段高度，不改变章节滚动进度。 */
@Composable
internal fun ReaderTextBlockLayout(
    index: TextBlockIndex,
    drawBlock: (DrawScope.(Int) -> Unit)? = null,
    accessibilityEnabled: Boolean = rememberReaderAccessibilityEnabled(),
    content: @Composable (Int) -> Unit
) {
    // 无障碍保留窗口内的原生文字语义，而不是为整章一次性挂载所有文本块。
    // 只有翻页模式等未提供窗口的调用者才保留全量节点。
    val viewport = LocalReaderTextViewport.current
    val isScrolling = LocalReaderTextScrolling.current
    if (!accessibilityEnabled && viewport != null && isScrolling != null && drawBlock != null) {
        ReaderDrawnTextBlockLayout(index, viewport, isScrolling, drawBlock, content)
        return
    }
    ReaderWindowedTextBlockLayout(index, viewport, content)
}

@Composable
private fun rememberReaderAccessibilityEnabled(): Boolean {
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
    return accessibilityEnabled
}

@Composable
private fun ReaderWindowedTextBlockLayout(
    index: TextBlockIndex,
    viewport: ReaderTextViewport?,
    content: @Composable (Int) -> Unit
) {
    var required by remember(index, viewport) {
        mutableStateOf(
            if (viewport == null) 0 until index.size
            else index.visibleRange(0f, viewport.height, viewport.height * 0.25f)
        )
    }
    var target by remember(index, viewport) {
        mutableStateOf(
            if (viewport == null) 0 until index.size
            else index.retainedRange(IntRange.EMPTY, 0f, viewport.height)
        )
    }
    var active by remember(index, viewport) { mutableStateOf(required) }
    LaunchedEffect(index, viewport, target) {
        if (viewport == null) return@LaunchedEffect
        while (active != target) {
            // 避免扩展缓存时在同一帧集中创建、测量一整屏的文本节点。
            withFrameNanos { }
            active = prefetchTextBlock(active, required, target)
        }
    }
    val positionModifier = if (viewport == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
        val origin = coordinates.positionInWindow().y
        val top = viewport.top - origin
        val bottom = viewport.bottom - origin
        required = index.visibleRange(top, bottom, viewport.height * 0.25f)
        val nextTarget = index.retainedRange(target, top, bottom)
        val nextActive = retainTextBlocks(active, required, nextTarget)
        if (nextTarget != target) target = nextTarget
        if (nextActive != active) active = nextActive
    }
    val range = active
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
