package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderDrawnTextBlockLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@RunWith(AndroidJUnit4::class)
class ReaderDrawnTextBlockLayoutTest {
    @Test
    fun scrollingFreezesSelectionAndIdleOnlyCreatesVisibleBlocks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val index = TextBlockIndex(List(10000) { 40 })
        val composed = mutableSetOf<Int>()
        val drawn = mutableSetOf<Int>()
        var creations = 0
        var measurements = 0
        var measuredHeight = 0
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current
                    var scrolling by remember { mutableStateOf(true) }
                    var position by remember { mutableIntStateOf(0) }
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
                            ReaderDrawnTextBlockLayout(index, viewport, isScrolling, { drawn += it }) { block ->
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
                    LaunchedEffect(Unit) {
                        suspend fun frames(count: Int) = repeat(count) { withFrameNanos { } }
                        suspend fun await(condition: () -> Boolean) = withTimeout(10000.milliseconds) {
                            while (!condition()) withFrameNanos { }
                        }
                        try {
                            await { measuredHeight == index.height }
                            frames(4)
                            assertTrue("滚动时只绘制，不创建选择节点", composed.isEmpty())
                            assertTrue(0 in drawn)
                            scrolling = false
                            await { 0 in composed }
                            val visible = index.visibleRange(0f, viewport.height)
                            assertTrue("第一批立即恢复全部可见文字的选择", visible.all { it in composed })
                            assertEquals("只挂载可见正文的选择节点", visible.count(), creations)
                            val initialCreations = creations
                            frames(6)
                            assertEquals("停稳后不再主动补齐屏外节点", initialCreations, creations)
                            scrolling = true
                            frames(2)
                            val previousCreations = creations
                            val previousMeasurements = measurements
                            for (block in listOf(100, 2000, 5000, 4000, 5000)) {
                                drawn.clear()
                                position = index.top(block)
                                frames(4)
                                assertTrue("快滑时必须直接绘制目标块", block in drawn)
                                assertEquals("滚动不能创建选择节点", previousCreations, creations)
                                assertEquals("滚动不能重新测量选择节点", previousMeasurements, measurements)
                                assertEquals(index.height, measuredHeight)
                            }
                            scrolling = false
                            await { 5000 in composed }
                            assertFalse("远离视口的旧节点应释放", 0 in composed)
                            val required = index.visibleRange(position.toFloat(), position + viewport.height)
                            assertEquals("跳转停稳后也只创建可见节点", required.toSet(), composed.toSet())
                            val stoppedCreations = creations
                            frames(6)
                            assertEquals("持续停稳不产生额外选择节点", stoppedCreations, creations)
                            drawn.clear()
                            position++
                            frames(3)
                            assertTrue("选择节点与直接绘制不能重叠", drawn.none { it in composed })
                            assertEquals(index.height, measuredHeight)
                        } catch (error: Throwable) {
                            failure.set(error)
                        } finally {
                            completed.countDown()
                        }
                    }
                }
            }
            assertTrue("等待选择节点调度验证完成", completed.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
