package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextBlockLayout
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderTextBlockLayoutTest {
    @Test
    fun scrollOnlyComposesNearbyBlocksWithoutChangingChapterHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assumeFalse(accessibility.isEnabled)
        val activity = instrumentation.startActivitySync(
            Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        val index = TextBlockIndex(List(10000) { 40 })
        val composed = mutableSetOf<Int>()
        val positioned = CountDownLatch(1)
        val jumped = CountDownLatch(1)
        var measuredHeight = 0
        var jump: (() -> Unit)? = null
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    val density = LocalDensity.current
                    val scroll = rememberScrollState()
                    val scope = rememberCoroutineScope()
                    var viewport by remember { mutableStateOf(ReaderTextViewport(0f, 600f)) }
                    jump = { scope.launch { scroll.scrollTo(index.top(5000)) } }
                    Box(Modifier.width(300.dp).height(240.dp).onGloballyPositioned {
                        val bounds = it.boundsInWindow()
                        viewport = ReaderTextViewport(bounds.top, bounds.bottom)
                    }) {
                        CompositionLocalProvider(LocalReaderTextViewport provides viewport) {
                            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                Box(Modifier.onGloballyPositioned {
                                    measuredHeight = it.size.height
                                    positioned.countDown()
                                }) {
                                    ReaderTextBlockLayout(index) { block ->
                                        DisposableEffect(block) {
                                            composed += block
                                            if (block == 5000) jumped.countDown()
                                            onDispose { composed -= block }
                                        }
                                        Box(Modifier.width(300.dp).height(with(density) { 40.toDp() }))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("等待章节完成初始布局", positioned.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(index.height, measuredHeight)
                assertTrue("不能组合整章一万个文本块", composed.size in 1..999)
                requireNotNull(jump).invoke()
            }
            assertTrue("跳转后应组合目标位置的文本块", jumped.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(5000 in composed)
                assertFalse("离屏的章首文本应当被释放", 0 in composed)
                assertTrue(composed.size in 1..999)
                assertEquals("滚动前后章节总高度不能变化", index.height, measuredHeight)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
