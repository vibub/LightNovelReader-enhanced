package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextScrolling
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderTextViewport
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RubyTextDrawingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cachedDrawingMatchesSelectableTextPixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        assumeFalse(accessibility.isEnabled)
        val fixtures = listOf(
            Fixture("正文。\n\n指挥官前进。", "指挥官", "公主"),
            Fixture("　　指挥官，下令！", "指挥官", "Commander-in-Chief"),
            Fixture("正文。\n\n指挥官下令。", "指挥官", "非常非常长的注释", fontSize = 24, gap = 0),
            Fixture("正文。\n\n指挥官前进。", "指挥官", "公主", gap = 30),
            Fixture("女王与指挥官。", "指挥官", "公主", styled = true),
            Fixture("abcdefghijklmnopqrstuvwx结束。", "abcdefghijklmnopqrstuvwx", "长原词拆分"),
            Fixture("女王与指挥官。", "指挥官", "公主", serif = true, dark = true),
            Fixture("مرحبا بالعالم\n\n指挥官。", "指挥官", "公主")
        )
        var fixture by mutableStateOf(fixtures.first())
        compose.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    // 奇数像素宽度的设备上，两侧也必须使用完全相同的排版宽度。
                    val columnWidth = with(LocalDensity.current) { (constraints.maxWidth / 2).toDp() }
                    Row {
                        for (direct in listOf(false, true)) {
                            Box(
                                Modifier.width(columnWidth)
                                    .testTag(if (direct) "direct" else "selectable")
                                    .background(if (fixture.dark) Color.Black else Color.White)
                            ) {
                                CompositionLocalProvider(
                                    LocalReaderTextViewport provides if (direct) ReaderTextViewport(-1000000f, 1000000f) else null,
                                    LocalReaderTextScrolling provides if (direct) ({ true }) else null
                                ) {
                                    SimpleTextComponentContent(
                                        modifier = Modifier.padding(8.dp),
                                        text = buildAnnotatedString {
                                            append(fixture.text)
                                            if (fixture.styled) addStyle(
                                                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                                                    textDecoration = TextDecoration.Underline),
                                                0, fixture.text.length
                                            )
                                        },
                                        fontSize = fixture.fontSize.sp,
                                        fontLineHeight = fixture.gap.sp,
                                        fontWeight = FontWeight.Normal,
                                        fontFamily = if (fixture.serif) FontFamily.Serif else FontFamily.Default,
                                        color = if (fixture.dark) Color.White else Color.Black,
                                        styleRanges = fixture.ranges()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        fixtures.forEach { current ->
            compose.runOnIdle { fixture = current }
            compose.waitForIdle()
            val expected = compose.onNodeWithTag("selectable").captureToImage().asAndroidBitmap()
            val actual = compose.onNodeWithTag("direct").captureToImage().asAndroidBitmap()
            assertEquals("新旧正文宽度一致：$current", expected.width, actual.width)
            assertEquals("新旧正文高度一致：$current", expected.height, actual.height)
            val oldPixels = IntArray(expected.width * expected.height)
            val newPixels = IntArray(actual.width * actual.height)
            expected.getPixels(oldPixels, 0, expected.width, 0, 0, expected.width, expected.height)
            actual.getPixels(newPixels, 0, actual.width, 0, 0, actual.width, actual.height)
            assertEquals("正文、原词、注释的像素必须一致：$current", 0,
                oldPixels.indices.count { oldPixels[it] != newPixels[it] })
        }
    }

    private data class Fixture(
        val text: String,
        val base: String,
        val annotation: String,
        val fontSize: Int = 18,
        val gap: Int = 7,
        val styled: Boolean = false,
        val serif: Boolean = false,
        val dark: Boolean = false
    ) {
        fun ranges(): List<SimpleTextStyleRange> = buildList {
            val start = text.indexOf(base)
            add(SimpleTextStyleRange(start, start + base.length, rubyText = annotation))
            if (text.contains("女王")) add(SimpleTextStyleRange(0, 2, rubyText = "Queen"))
            if (styled) add(SimpleTextStyleRange(0, text.length, fontWeight = 700, italic = true, underline = true))
        }
    }
}
