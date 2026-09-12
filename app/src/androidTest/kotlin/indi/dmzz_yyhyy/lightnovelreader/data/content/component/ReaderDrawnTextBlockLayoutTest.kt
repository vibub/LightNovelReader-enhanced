package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderDrawnTextBlockLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderDrawnTextBlockLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun scrollingDrawsNewBlocksWithoutCreatingOrMeasuringSelectableNodes() {
        val index = TextBlockIndex(List(10000) { 40 })
        var scrolling by mutableStateOf(false)
        var position by mutableIntStateOf(0)
        val composed = mutableSetOf<Int>()
        val drawn = mutableSetOf<Int>()
        var creations = 0
        var measurements = 0
        var measuredHeight = 0
        compose.setContent {
            val density = LocalDensity.current
            val isScrolling = remember { { scrolling } }
            var viewport by remember { mutableStateOf(ReaderTextViewport(0f, 600f)) }
            Box(Modifier.fillMaxWidth().height(240.dp).clipToBounds().onGloballyPositioned {
                val bounds = it.boundsInWindow()
                viewport = ReaderTextViewport(bounds.top, bounds.bottom)
            }) {
                Box(
                    Modifier.offset { IntOffset(0, -position) }
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .onGloballyPositioned { measuredHeight = it.size.height }
                ) {
                    ReaderDrawnTextBlockLayout(
                        index = index,
                        viewport = viewport,
                        isScrolling = isScrolling,
                        drawBlock = { drawn += it }
                    ) { block ->
                        DisposableEffect(block) {
                            composed += block
                            creations++
                            onDispose { composed -= block }
                        }
                        Box(Modifier.fillMaxWidth().height(with(density) { 40.toDp() }).layout { measurable, constraints ->
                            measurements++
                            val placeable = measurable.measure(constraints)
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        })
                    }
                }
            }
        }
        compose.waitForIdle()
        var previousCreations = 0
        var previousMeasurements = 0
        compose.runOnIdle {
            assertTrue("停稳后保留可见区文字选择", 0 in composed)
            assertTrue("不能组合整章", composed.size in 1..999)
            assertEquals(index.height, measuredHeight)
            previousCreations = creations
            previousMeasurements = measurements
            drawn.clear()
            scrolling = true
        }
        for (block in listOf(100, 2000, 5000, 4000, 5000)) {
            compose.runOnIdle { position = index.top(block) }
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue("快速跳转必须直接绘制目标块 $block", block in drawn)
                assertEquals("持续滚动不能创建选择节点", previousCreations, creations)
                assertEquals("持续滚动不能重新测量选择节点", previousMeasurements, measurements)
                assertEquals("直接绘制不能改变章节高度", index.height, measuredHeight)
                drawn.clear()
            }
        }
        compose.runOnIdle { scrolling = false }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("停止后恢复目标位置的文字选择", 5000 in composed)
            assertFalse("释放之前远离视口的选择节点", 0 in composed)
            assertTrue(composed.size in 1..999)
            assertEquals(index.height, measuredHeight)
            drawn.clear()
        }
        // 强制一帧绘制，选择节点覆盖的块不能再绘制一次。
        compose.runOnIdle { position++ }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("选择节点与直接绘制不能重复绘制同一块", drawn.none { it in composed })
        }
    }
}
