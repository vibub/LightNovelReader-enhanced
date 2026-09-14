package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextScrolling
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
class ReaderAccessibleTextBlockLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun accessibilityKeepsNativeTextAndScrollActionsWithoutMountingWholeChapter() {
        val index = TextBlockIndex(List(10000) { 40 })
        val composed = mutableSetOf<Int>()
        var measuredHeight = 0
        var drawings = 0
        rule.setContent {
            val density = LocalDensity.current
            val scroll = rememberScrollState()
            var viewport by remember { mutableStateOf(ReaderTextViewport(0f, 600f)) }
            Box(Modifier.width(300.dp).height(240.dp).onGloballyPositioned {
                val bounds = it.boundsInWindow()
                viewport = ReaderTextViewport(bounds.top, bounds.bottom)
            }) {
                CompositionLocalProvider(
                    LocalReaderTextViewport provides viewport,
                    // 即使正在滚动，无障碍分支也必须有原生文字，而不是只有 Canvas。
                    LocalReaderTextScrolling provides { true }
                ) {
                    Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        SelectionContainer {
                            Box(Modifier.onGloballyPositioned { measuredHeight = it.size.height }) {
                                ReaderTextBlockLayout(
                                    index = index,
                                    drawBlock = { drawings++ },
                                    accessibilityEnabled = true
                                ) { block ->
                                    DisposableEffect(block) {
                                        composed += block
                                        onDispose { composed -= block }
                                    }
                                    BasicText("正文$block", Modifier.height(with(density) { 40.toDp() }))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("正文0").assertExists()
        rule.onNodeWithText("正文9999").assertDoesNotExist()
        rule.runOnIdle {
            assertTrue("无障碍模式不能挂载整章一万个块", composed.size in 1..999)
            assertEquals(index.height, measuredHeight)
            assertEquals("无障碍文字必须由原生节点显示", 0, drawings)
        }
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue("保留辅助服务可调用的滚动动作", it(0f, index.top(5000).toFloat()))
        }
        rule.waitUntil(10000) { 5000 in composed }
        rule.onNodeWithText("正文5000").assertExists()
        rule.runOnIdle {
            assertFalse(0 in composed)
            assertTrue(composed.size in 1..999)
            assertEquals(index.height, measuredHeight)
        }
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            assertTrue(it(0f, -index.top(5000).toFloat()))
        }
        rule.waitUntil(10000) { 0 in composed }
        rule.onNodeWithText("正文0").assertExists()
        rule.runOnIdle { assertEquals(index.height, measuredHeight) }
    }

    @Test
    fun enablingAccessibilitySwitchesFromDrawingToBoundedNativeText() {
        val index = TextBlockIndex(List(10000) { 40 })
        var accessibilityEnabled by mutableStateOf(false)
        val composed = mutableSetOf<Int>()
        var drawings = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalReaderTextViewport provides ReaderTextViewport(0f, 600f),
                LocalReaderTextScrolling provides { true }
            ) {
                Box(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                    ReaderTextBlockLayout(index, { drawings++ }, accessibilityEnabled) { block ->
                        DisposableEffect(block) {
                            composed += block
                            onDispose { composed -= block }
                        }
                        BasicText("正文$block", Modifier.height(with(density) { 40.toDp() }))
                    }
                }
            }
        }
        rule.waitUntil(10000) { drawings > 0 }
        rule.runOnIdle {
            assertTrue(composed.isEmpty())
            accessibilityEnabled = true
        }
        rule.onNodeWithText("正文0").assertExists()
        rule.runOnIdle {
            assertTrue(composed.size in 1..999)
            accessibilityEnabled = false
        }
        rule.waitUntil(10000) { composed.isEmpty() }
    }

    @Test
    fun callersWithoutViewportStillKeepAllText() {
        val index = TextBlockIndex(List(20) { 40 })
        rule.setContent {
            val density = LocalDensity.current
            ReaderTextBlockLayout(index, accessibilityEnabled = true) { block ->
                BasicText("正文$block", Modifier.height(with(density) { 40.toDp() }))
            }
        }
        rule.onNodeWithText("正文0").assertExists()
        rule.onNodeWithText("正文19").assertExists()
    }
}
