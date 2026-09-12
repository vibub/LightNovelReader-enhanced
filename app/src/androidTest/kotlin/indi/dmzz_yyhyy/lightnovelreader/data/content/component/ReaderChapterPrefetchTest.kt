package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.createReaderLazyListState
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderChapterPrefetchTest {
    @Test
    fun reversingWithinChapterDoesNotComposeAdjacentChapters() {
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
                    val state = remember { createReaderLazyListState().apply { requestScrollToItem(1, 6000) } }
                    val created = remember { mutableListOf<Int>() }
                    val positioned = remember { CompletableDeferred<Unit>() }
                    val nextPositioned = remember { CompletableDeferred<Unit>() }
                    LazyColumn(Modifier.fillMaxWidth().height(240.dp), state = state) {
                        items(listOf(0, 1, 2), key = { it }) { chapter ->
                            DisposableEffect(chapter) {
                                created += chapter
                                onDispose { }
                            }
                            Box(Modifier.fillMaxWidth().height(12000.dp).onGloballyPositioned {
                                if (chapter == 1) positioned.complete(Unit)
                                if (chapter == 2) nextPositioned.complete(Unit)
                            })
                        }
                    }
                    LaunchedEffect(state) {
                        try {
                            positioned.await()
                            repeat(8) { attempt ->
                                state.scrollBy(if (attempt % 2 == 0) 1000f else -1000f)
                                repeat(20) { withFrameNanos { } }
                            }
                            assertEquals("章内往返滑动不能预组合前后两章", listOf(1), created)
                            assertEquals(1, state.firstVisibleItemIndex)
                            state.scrollToItem(2)
                            nextPositioned.await()
                            assertTrue("真正进入下一章时仍应组合正文", 2 in created)
                            assertEquals(2, state.firstVisibleItemIndex)
                        } catch (error: Throwable) {
                            failure.set(error)
                        } finally {
                            completed.countDown()
                        }
                    }
                }
            }
            assertTrue("等待往返滑动与章节切换验证完成", completed.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
