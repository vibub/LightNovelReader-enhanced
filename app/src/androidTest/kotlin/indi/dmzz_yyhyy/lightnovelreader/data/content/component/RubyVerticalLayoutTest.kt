package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RubyVerticalLayoutTest {
    private val text = "　　鲜血女王陛下还是一样，下指示都没在客气的。\n\n　　不……这已经不是没在客气了吧。\n\n　　明明有这么夸张的指挥官坐镇，共和国怎么还会打败仗啊……\n\n　　亨利坐在改造成工兵车的重机驾驶座上说道，同袍尼诺中尉与卡莱里中尉惊悚地回应。\n\n　　为了因应战壕或反战车壕在战斗中损坏需要修补的情况发生，他们在炮兵阵地带等着出动。乘坐的是前方配备推土刀并随便加装了一些铁板，算是比赤手空拳多一点防护的推土机。\n\n　　大概是习以为常了，萨费拉赫默默从装甲帆布罩下多加了点增幅的驾驶舱里推开舱门。"
    private val ranges get() = listOf(
        range("指挥官", "公主"),
        range("炮兵阵地", "豪雨")
    )

    private fun range(base: String, annotation: String): SimpleTextStyleRange {
        val start = text.indexOf(base)
        return SimpleTextStyleRange(start, start + base.length, rubyText = annotation)
    }

    @Test
    fun paragraphSpacingAndRubyBoundsAcrossReaderStyles() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = Density(1f, 1f)
            val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
            for (width in listOf(180, 340)) {
                for (fontSize in listOf(15, 24, 32)) {
                    for (gap in listOf(0, 7, 20, 60)) {
                        for (font in listOf(FontFamily.Default, FontFamily.Serif)) {
                            val style = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize + gap).sp, fontFamily = font)
                            val ruby = measureRubyText(AnnotatedString(text), ranges, style, measurer, density, width)
                            val original = measurer.measure(
                                AnnotatedString(text), style,
                                placeholders = ruby.runs.map { run ->
                                    AnnotatedString.Range(
                                        Placeholder(run.placeholder.width, with(density) { run.base.size.height.toSp() }, run.placeholder.placeholderVerticalAlign),
                                        run.start, run.end
                                    )
                                },
                                constraints = Constraints(maxWidth = width)
                            )
                            assertEquals("不能增加空行", original.lineCount, ruby.lines.size)
                            ruby.lines.forEachIndexed { index, line ->
                                assertEquals(original.getLineStart(index), line.start)
                                assertEquals(original.getLineEnd(index), line.end)
                                val expectedHeight = kotlin.math.ceil(original.getLineBottom(index) - original.getLineTop(index)).toInt()
                                if (line.runs.isEmpty()) {
                                    assertEquals("只抵扣被注释复用的空白", expectedHeight, line.height + line.reclaimedHeight)
                                    if (!line.text.isBlank()) assertEquals(0, line.reclaimedHeight)
                                } else if (gap == 60) {
                                    assertEquals("原行距足够时注释不应继续撑高", expectedHeight, line.height)
                                }
                                line.runs.forEachIndexed { runIndex, run ->
                                    val rect = requireNotNull(line.layout.placeholderRects[runIndex])
                                    assertTrue("注释不得侵入上一行：$rect", line.topPadding + rect.top >= -1f)
                                    assertTrue("原词不得越过本行底部", line.topPadding + rect.bottom <= line.height + 1f)
                                    assertEquals("上侧安全间隙不变", kotlin.math.ceil(fontSize * 0.1f).toInt(), run.annotationTop)
                                    val annotationBottom = run.annotation.maxOf {
                                        run.annotationTop + run.annotationHeight -
                                            (it.size.height - it.rubyInkBounds().bottom).coerceAtLeast(0f) * run.annotationPlacement.scale
                                    }
                                    val visibleGap = run.baseTop + run.base.rubyInkBounds().top - annotationBottom
                                    val expectedGap = kotlin.math.ceil(fontSize * 0.06f).toInt()
                                    assertTrue("可见字形之间保留小间隙", visibleGap >= expectedGap - 0.01f && visibleGap < expectedGap + 1.01f)
                                    run.annotation.forEach {
                                        assertEquals((fontSize * 0.6f).sp, it.layoutInput.style.fontSize)
                                    }
                                }
                            }
                            val pages = ruby.pageRanges(260)
                            assertEquals(text, pages.joinToString("") { text.slice(it) })
                            pages.forEach { page ->
                                val pageLines = ruby.lines.filter { it.start in page }
                                assertTrue("分页不得漏算注释高度", pageLines.size == 1 || pageLines.sumOf { it.height } <= 260)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun paragraphStartRubyUsesExistingGapWithoutMovingBodyBaseline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = Density(1f, 1f)
            val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr)
            val fixture = "第一段正文。\n\n第二段正文。\n\n第三段指挥官正文。"
            val start = fixture.indexOf("指挥官")
            val ruby = measureRubyText(
                AnnotatedString(fixture), listOf(SimpleTextStyleRange(start, start + 3, rubyText = "公主")),
                TextStyle(fontSize = 20.sp, lineHeight = 28.sp), measurer, density, 340
            )
            var top = 0
            val bodyBaselines = ruby.lines.mapNotNull { line ->
                val baseline = if (line.text.isBlank()) null else top + line.topPadding + line.layout.firstBaseline
                top += line.height
                baseline
            }
            assertEquals(3, bodyBaselines.size)
            assertTrue("应当复用现有段间空白", ruby.lines.sumOf { it.reclaimedHeight } > 0)
            assertEquals(
                "空间足够时，带注释与不带注释的正文段间距一致",
                bodyBaselines[1] - bodyBaselines[0], bodyBaselines[2] - bodyBaselines[1], 1f
            )
            ruby.pageRanges(75).forEach { page ->
                val slicedRanges = ruby.runs.filter { it.start in page }.map {
                    SimpleTextStyleRange(it.start - page.first, it.end - page.first, rubyText = "公主")
                }
                val sliced = measureRubyText(
                    AnnotatedString(fixture.slice(page)), slicedRanges,
                    TextStyle(fontSize = 20.sp, lineHeight = 28.sp), measurer, density, 340
                )
                if (sliced.lines.isNotEmpty()) assertTrue("分页重测不能溢出", sliced.lines.sumOf { it.height } <= 75)
            }
        }
    }

    @Test
    fun captureProductionReaderComponent() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
        val rendered = CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        Column(Modifier.width(360.dp).background(Color(0xFFFFF7FF)).padding(16.dp).onGloballyPositioned {
                            activity.window.decorView.viewTreeObserver.registerFrameCommitCallback { rendered.countDown() }
                        }) {
                            SimpleTextComponentContent(
                                modifier = Modifier,
                                text = AnnotatedString(text),
                                fontSize = 18.sp,
                                fontLineHeight = 7.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = FontFamily.Default,
                                color = Color.Black,
                                styleRanges = ranges
                            )
                        }
                    }
                }
            }
            assertTrue("等待实际界面完成绘制", rendered.await(15, TimeUnit.SECONDS))
            val view = activity.window.decorView
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val copied = CountDownLatch(1)
            var result = -1
            PixelCopy.request(activity.window, image, {
                result = it
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
            assertTrue("等待窗口截图", copied.await(10, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
            File(context.getExternalFilesDir(null), "ruby-vertical-layout.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
