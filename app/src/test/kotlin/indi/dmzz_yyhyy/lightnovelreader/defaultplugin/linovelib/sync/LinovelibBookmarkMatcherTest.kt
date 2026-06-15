package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibBookmarkMatcherTest {
    @Test
    fun resolveMatchesBaseChapterIdFromUrl() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("12345", "第一章"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "https://www.linovelib.com/novel/1/12345_2.html?from=bookcase",
            remoteTitle = "",
            volumes = volumes
        )

        assertEquals("12345", result?.id)
    }

    @Test
    fun resolveUsesDirectRemoteChapterIdWhenCatalogOmitsChapter() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("129584", "插图")),
            volume("第八卷", chapter("257544", "序章"), chapter("259145", "后记")),
            volume("第九卷", chapter("288509", "插图"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "257543",
            remoteTitle = "插图",
            volumes = volumes
        )

        assertEquals("257543", result?.id)
        assertEquals("插图", result?.title)
    }

    @Test
    fun resolvePrefersUniqueCatalogTitleOverDirectRemoteChapterIdFallback() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("12345", "第一章"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "999999",
            remoteTitle = "第一章",
            volumes = volumes
        )

        assertEquals("12345", result?.id)
        assertEquals("第一章", result?.title)
    }

    @Test
    fun resolveUsesDirectRemoteChapterIdWhenCatalogIsEmpty() {
        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "257543",
            remoteTitle = "插图",
            volumes = BookVolumes("2734", emptyList())
        )

        assertEquals("257543", result?.id)
        assertEquals("插图", result?.title)
    }

    @Test
    fun resolveDoesNotUseUnparsedReadBookcaseHrefAsDirectChapterId() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("129584", "插图")),
            volume("第二卷", chapter("137798", "插图"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "javascript:read_bookcase(2734, 257543, 8507052, 1);",
            remoteTitle = "插图",
            volumes = volumes
        )

        assertNull(result)
    }

    @Test
    fun resolveNormalizesFullWidthNumbersAndPunctuation() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "第1章 开始"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "第１章：开始",
            volumes = volumes
        )

        assertEquals("1", result?.id)
    }

    @Test
    fun resolveMatchesUniqueShortIllustration() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "插图"), chapter("2", "第一章"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "插图",
            volumes = volumes
        )

        assertEquals("1", result?.id)
    }

    @Test
    fun resolveKeepsDuplicateShortIllustrationsUnmatchedWithoutVolumeContext() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "插图")),
            volume("第二卷", chapter("2", "插图"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "插图",
            volumes = volumes
        )

        assertNull(result)
    }

    @Test
    fun resolveUsesVolumeContextForDuplicateShortTitle() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "插图")),
            volume("第二卷", chapter("2", "插图"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "第2卷 插图",
            volumes = volumes
        )

        assertEquals("2", result?.id)
    }

    @Test
    fun resolveKeepsDuplicateAfterwordSynonymsUnmatchedWithoutVolumeContext() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "后记")),
            volume("第二卷", chapter("2", "后记"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "Afterword",
            volumes = volumes
        )

        assertNull(result)
    }

    @Test
    fun resolveWeakensStoreBonusPrefixWhenUnique() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "Animate特典 间章 第1.2章 快乐的快乐的文化祭！班级会议篇"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "间章 第1.2章 快乐的快乐的文化祭！班级会议篇",
            volumes = volumes
        )

        assertEquals("1", result?.id)
    }

    @Test
    fun resolveKeepsContainsMatchUnresolvedWhenMultipleCandidatesMatch() {
        val volumes = bookVolumes(
            volume("第一卷", chapter("1", "第一章 快乐的文化祭")),
            volume("第二卷", chapter("2", "第二章 快乐的文化祭"))
        )

        val result = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = "快乐的文化祭",
            volumes = volumes
        )

        assertNull(result)
    }

    @Test
    fun matchesTitleAcceptsShortTitleSynonyms() {
        assertTrue(LinovelibBookmarkMatcher.matchesTitle("Afterword", "后记"))
    }

    private fun bookVolumes(vararg volumes: Volume): BookVolumes = BookVolumes("1", volumes.toList())

    private fun volume(title: String, vararg chapters: ChapterInformation): Volume =
        Volume(title, title, chapters.toList())

    private fun chapter(id: String, title: String): ChapterInformation = ChapterInformation(id, title)
}
