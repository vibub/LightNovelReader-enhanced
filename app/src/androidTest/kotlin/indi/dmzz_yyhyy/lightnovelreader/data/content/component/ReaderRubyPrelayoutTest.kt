package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderRubyTextCache
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextScrolling
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderRubyTextCache
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.RubyTextEnvironment
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.RubyTextKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.prepareRubyText
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.readerRubyTextStyle
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderRubyPrelayoutTest {
    private fun key(): RubyTextKey {
        val paragraph = "这是拥有注释的正文，也要保留普通文字的换行。\n\n"
        val data = SimpleTextComponentData(
            text = paragraph.repeat(30),
            styleRanges = List(30) { index ->
                SimpleTextStyleRange(
                    start = paragraph.length * index + 2,
                    end = paragraph.length * index + 6,
                    rubyText = "annotation 测试"
                )
            }
        )
        return RubyTextKey(data.toAnnotatedString(), data.styleRanges)
    }

    @Test
    fun backgroundLayoutMatchesForegroundAndInvalidatesForEnvironmentChanges() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val environment = RubyTextEnvironment(
            TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontFamily = FontFamily.Serif),
            Density(2f, 1f), LayoutDirection.Ltr,
            createFontFamilyResolver(instrumentation.targetContext), 600
        )
        val key = key()
        val background = withContext(Dispatchers.Default) {
            prepareRubyText(key, environment, environment.newMeasurer())
        }
        instrumentation.runOnMainSync {
            val foreground = prepareRubyText(key, environment, environment.newMeasurer())
            assertEquals(foreground.blocks.map { it.height }, background.blocks.map { it.height })
            assertEquals(foreground.index.height, background.index.height)
            assertEquals(foreground.layout.lines.map { it.start to it.end }, background.layout.lines.map { it.start to it.end })
            foreground.layout.runs.zip(background.layout.runs).forEach { (expected, actual) ->
                assertEquals(expected.base.size, actual.base.size)
                assertEquals(expected.baseTop, actual.baseTop)
                assertEquals(expected.annotationPlacement, actual.annotationPlacement)
                assertEquals(expected.placeholder, actual.placeholder)
            }
            assertFalse(background.hasStaleFonts)
            val cache = ReaderRubyTextCache(environment)
            cache.retain(setOf(key))
            cache.put(key, environment, background)
            assertSame(background, cache.get(key, environment))
            assertSame(background, cache.get(key.copy(), environment))
            assertNull(cache.get(key, environment.copy(width = 599)))
            assertNull(cache.get(key, environment.copy(density = Density(2f, 1.2f))))
            assertNull(cache.get(key, environment.copy(direction = LayoutDirection.Rtl)))
            assertNull(cache.get(key, environment.copy(style = environment.style.copy(fontSize = 19.sp))))
            assertNull(cache.get(key, environment.copy(style = environment.style.copy(fontFamily = FontFamily.Monospace))))
            assertNull(cache.get(key.copy(ranges = emptyList()), environment))
        }
    }

    @Test
    fun cancelledPrelayoutDoesNotProducePartialResult() {
        val environment = RubyTextEnvironment(
            TextStyle(fontSize = 18.sp), Density(1f), LayoutDirection.Ltr,
            createFontFamilyResolver(InstrumentationRegistry.getInstrumentation().targetContext), 400
        )
        var checks = 0
        var cancelled = false
        try {
            prepareRubyText(key(), environment, environment.newMeasurer()) {
                if (++checks == 4) throw CancellationException("取消过期排版")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(4, checks)
    }

    @Test
    fun enteringAndReenteringChapterReusesPreparedInstance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ComponentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val density = LocalDensity.current
                        val style = readerRubyTextStyle(18.sp, 8.sp, FontWeight.Normal, FontFamily.Serif, Color.Black)
                        val environment = RubyTextEnvironment(
                            style, Density(density.density, density.fontScale), LocalLayoutDirection.current,
                            LocalFontFamilyResolver.current, constraints.maxWidth
                        )
                        val cache = remember(environment) { ReaderRubyTextCache(environment) }
                        val key = remember { key() }
                        var show by remember { mutableStateOf(false) }
                        var positioned by remember { mutableStateOf(false) }
                        var measuredWidth by remember { mutableStateOf(0) }
                        CompositionLocalProvider(
                            LocalReaderRubyTextCache provides cache,
                            LocalReaderTextScrolling provides { true },
                            LocalReaderTextViewport provides ReaderTextViewport(0f, 600f)
                        ) {
                            if (show) {
                                SimpleTextComponentContent(
                                    Modifier.onGloballyPositioned {
                                        measuredWidth = it.size.width
                                        positioned = true
                                    },
                                    key.text, 18.sp, 8.sp, FontWeight.Normal,
                                    FontFamily.Serif, Color.Black, key.ranges
                                )
                            }
                        }
                        LaunchedEffect(cache) {
                            try {
                                cache.retain(setOf(key))
                                val prepared = withContext(Dispatchers.Default) {
                                    prepareRubyText(key, environment, environment.newMeasurer())
                                }
                                cache.put(key, environment, prepared)
                                repeat(2) {
                                    positioned = false
                                    show = true
                                    while (!positioned) withFrameNanos { }
                                    withFrameNanos { }
                                    assertEquals(environment.width, measuredWidth)
                                    assertSame("跨章不能重新测量并替换预排版结果", prepared, cache.get(key, environment))
                                    show = false
                                    repeat(2) { withFrameNanos { } }
                                }
                            } catch (error: Throwable) {
                                failure.set(error)
                            } finally {
                                completed.countDown()
                            }
                        }
                    }
                }
            }
            assertTrue("等待跨章缓存复用验证完成", completed.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
