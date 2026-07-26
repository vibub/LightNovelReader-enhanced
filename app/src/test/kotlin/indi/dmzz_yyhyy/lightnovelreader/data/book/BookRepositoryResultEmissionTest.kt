package indi.dmzz_yyhyy.lightnovelreader.data.book

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.book.Volume
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BookRepositoryResultEmissionTest {
    @Test
    fun remoteErrorDoesNotReplaceUsableLocalValue() {
        assertFalse(shouldEmitRemoteResult<String, String>(true, Err("network")))
    }

    @Test
    fun remoteErrorIsEmittedWithoutUsableLocalValue() {
        assertTrue(shouldEmitRemoteResult<String, String>(false, Err("network")))
    }

    @Test
    fun remoteSuccessReplacesUsableLocalValue() {
        assertTrue(shouldEmitRemoteResult(true, Ok("fresh")))
    }

    @Test
    fun titleOnlyBookInformationIsPlaceholder() {
        assertFalse(
            isUsableBookInformationData(
                id = "2890",
                title = "义妹生活",
                hasCover = false,
                subtitle = "",
                author = "",
                description = "",
                hasTags = false,
                publishingHouse = "",
                wordCount = 0,
                lastUpdated = LocalDateTime.MIN,
                isComplete = false
            )
        )
    }

    @Test
    fun parsedBookInformationIsUsable() {
        assertTrue(
            isUsableBookInformationData(
                id = "2890",
                title = "义妹生活",
                hasCover = false,
                subtitle = "",
                author = "三河ごーすと",
                description = "",
                hasTags = false,
                publishingHouse = "",
                wordCount = 0,
                lastUpdated = LocalDateTime.MIN,
                isComplete = false
            )
        )
    }

    @Test
    fun emptyVolumesAreNotUsable() {
        assertFalse(isUsableBookVolumes(BookVolumes("2890", emptyList())))
    }

    @Test
    fun volumesWithChapterAreUsable() {
        assertTrue(
            isUsableBookVolumes(
                BookVolumes(
                    bookId = "2890",
                    volumes = listOf(
                        Volume(
                            volumeId = "2890_0",
                            volumeTitle = "第一卷",
                            chapters = listOf(ChapterInformation("142735", "插图"))
                        )
                    )
                )
            )
        )
    }

    @Test
    fun chapterWithoutComponentsIsNotUsable() {
        assertFalse(
            isUsableChapterContent(
                ChapterContent(
                    id = "142735",
                    title = "插图",
                    content = buildJsonObject { putJsonArray("components") {} },
                    prevChapter = null,
                    nextChapter = "142736"
                )
            )
        )
    }

    @Test
    fun chapterWithComponentIsUsable() {
        assertTrue(
            isUsableChapterContent(
                ChapterContent(
                    id = "142735",
                    title = "插图",
                    content = buildJsonObject {
                        putJsonArray("components") {
                            addJsonObject { }
                        }
                    },
                    prevChapter = null,
                    nextChapter = "142736"
                )
            )
        )
    }
}
