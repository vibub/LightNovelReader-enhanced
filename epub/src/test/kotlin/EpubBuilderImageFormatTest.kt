import io.nightfish.potatoepub.builder.EpubBuilder
import java.time.LocalDateTime
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class EpubBuilderImageFormatTest {
    @Test
    fun pngCoverKeepsPngHrefMimeAndBytes() {
        val directory = createTempDirectory("epub-image-test")
        try {
            val imageBytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            )
            val image = directory.resolve("cover.png")
            image.writeBytes(imageBytes)
            val epubFile = directory.resolve("book.epub")

            EpubBuilder().apply {
                title = "测试书"
                modifier = LocalDateTime.now()
                cover(image.toFile())
            }.build().save(epubFile.toFile())

            ZipFile(epubFile.toFile()).use { zip ->
                val coverEntry = zip.getEntry("EPUB/cover.png")
                assertTrue(coverEntry != null)
                assertContentEquals(imageBytes, zip.getInputStream(coverEntry).readBytes())
                val opf = zip.getInputStream(zip.getEntry("EPUB/content.opf")).readBytes()
                    .decodeToString()
                assertTrue(opf.contains("href=\"cover.png\""))
                assertTrue(opf.contains("media-type=\"image/png\""))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
