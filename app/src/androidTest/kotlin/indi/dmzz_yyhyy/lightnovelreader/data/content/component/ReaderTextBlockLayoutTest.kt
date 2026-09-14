package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextBlockLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTextBlockLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scrollOnlyComposesNearbyBlocksWithoutChangingChapterHeight() {
        val index = TextBlockIndex(List(10000) { 40 })
        val composed = mutableSetOf<Int>()
        var viewport by mutableStateOf(ReaderTextViewport(0f, 600f))
        var measuredHeight = 0
        var creations = 0
        rule.setContent {
            val density = LocalDensity.current
            val scroll = rememberScrollState()
            Box(Modifier.width(300.dp).height(240.dp).onGloballyPositioned {
                val bounds = it.boundsInWindow()
                viewport = ReaderTextViewport(bounds.top, bounds.bottom)
            }) {
                CompositionLocalProvider(LocalReaderTextViewport provides viewport) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        Box(Modifier.onGloballyPositioned { measuredHeight = it.size.height }) {
                            ReaderTextBlockLayout(index, accessibilityEnabled = false) { block ->
                                DisposableEffect(block) {
                                    composed += block
                                    creations++
                                    onDispose { composed -= block }
                                }
                                Box(Modifier.width(300.dp).height(with(density) { 40.toDp() }))
                            }
                        }
                    }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(index.height, measuredHeight)
            assertTrue("不能组合整章一万个文本块", composed.size in 1..999)
        }
        fun scrollBy(delta: Float) {
            rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
                assertTrue(it(0f, delta))
            }
            rule.waitForIdle()
        }
        val position = index.top(5000).toFloat()
        scrollBy(position)
        rule.waitUntil(15000) {
            index.retainedRange(IntRange.EMPTY, position, position + viewport.height).all { it in composed }
        }
        val creationsBeforeReversal = rule.runOnIdle {
            assertTrue(5000 in composed)
            assertFalse("离屏的章首文本应当被释放", 0 in composed)
            assertTrue(composed.size in 1..999)
            assertEquals("滚动前后章节总高度不能变化", index.height, measuredHeight)
            creations
        }
        repeat(20) { scrollBy(if (it % 2 == 0) 40f else -40f) }
        rule.runOnIdle {
            assertEquals("缓存内往返滑动不应重建文本块", creationsBeforeReversal, creations)
            assertEquals(index.height, measuredHeight)
        }
    }
}
