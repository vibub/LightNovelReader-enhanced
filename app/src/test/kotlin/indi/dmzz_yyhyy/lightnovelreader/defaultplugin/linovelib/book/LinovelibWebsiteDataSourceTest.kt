package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibWebsiteDataSourceTest {
    @Test
    fun mergeLinovelibPagedTextPartsAddsBlankLineBetweenPagedTextParts() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("上一页末段"),
            LinovelibChapterContentParser.Part.Text("下一页首段")
        )

        assertEquals(
            listOf(LinovelibChapterContentParser.Part.Text("上一页末段\n\n下一页首段")),
            parts.mergeLinovelibPagedTextParts()
        )
    }

    @Test
    fun mergeLinovelibPagedTextPartsDoesNotMergeTextAcrossImages() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("上一页末段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("图片后正文")
        )

        assertEquals(parts, parts.mergeLinovelibPagedTextParts())
    }

    @Test
    fun mergeLinovelibPagedTextPartsKeepsImageBeforePagedText() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("下一页首段")
        )

        assertEquals(parts, parts.mergeLinovelibPagedTextParts())
    }

    @Test
    fun mergeLinovelibPagedTextPartsMergesEachTextRunAroundImages() {
        val parts = listOf(
            LinovelibChapterContentParser.Part.Text("第一段"),
            LinovelibChapterContentParser.Part.Text("第二段"),
            LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
            LinovelibChapterContentParser.Part.Text("第三段"),
            LinovelibChapterContentParser.Part.Text("第四段")
        )

        assertEquals(
            listOf(
                LinovelibChapterContentParser.Part.Text("第一段\n\n第二段"),
                LinovelibChapterContentParser.Part.Image("https://www.linovelib.com/image.jpg"),
                LinovelibChapterContentParser.Part.Text("第三段\n\n第四段")
            ),
            parts.mergeLinovelibPagedTextParts()
        )
    }
}
