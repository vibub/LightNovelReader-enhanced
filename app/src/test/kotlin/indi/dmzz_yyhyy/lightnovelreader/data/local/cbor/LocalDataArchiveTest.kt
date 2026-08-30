package indi.dmzz_yyhyy.lightnovelreader.data.local.cbor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataArchiveTest {
    @Test
    fun roundTripKeepsDataAndResourceBytes() {
        val sourceDirectory = createTempDirectory("lnr-archive-source")
        val stagingDirectory = createTempDirectory("lnr-archive-staging")
        try {
            val resource = sourceDirectory.resolve("image.png")
            val resourceBytes = byteArrayOf(0x01, 0x02, 0x03, 0x7f)
            resource.writeBytes(resourceBytes)
            val data = byteArrayOf(0x10, 0x20, 0x30)
            val output = ByteArrayOutputStream()

            LocalDataArchive.write(
                output = output,
                data = data,
                resources = listOf(
                    LocalDataArchive.ResourceFile(
                        sourceUri = "content://images/1",
                        targetPath = "offline_content_images/book/chapter/image.png",
                        file = resource.toFile()
                    )
                )
            )

            val result = LocalDataArchive.read(
                input = ByteArrayInputStream(output.toByteArray()),
                stagingDirectory = stagingDirectory.toFile()
            )
            val manifest = result.manifest
            assertNotNull(manifest)
            assertArrayEquals(data, result.data)
            assertEquals(1, manifest!!.resources.size)
            assertArrayEquals(
                resourceBytes,
                stagingDirectory.resolve(manifest.resources.single().targetPath).readBytes()
            )
        } finally {
            sourceDirectory.toFile().deleteRecursively()
            stagingDirectory.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyRawCborArchiveRemainsReadable() {
        val data = "legacy".encodeToByteArray()

        val result = LocalDataArchive.read(ByteArrayInputStream(data))

        assertArrayEquals(data, result.data)
        assertEquals(null, result.manifest)
    }

    @Test
    fun zipDataOnlyArchiveRemainsReadable() {
        val output = ByteArrayOutputStream()
        val data = "legacy-zip".encodeToByteArray()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(LocalDataArchive.DATA_ENTRY))
            zip.write(data)
            zip.closeEntry()
        }

        val result = LocalDataArchive.read(ByteArrayInputStream(output.toByteArray()))

        assertArrayEquals(data, result.data)
        assertEquals(null, result.manifest)
    }

    @Test
    fun duplicateResourceSourceUriIsRejectedBeforeWriting() {
        val directory = createTempDirectory("lnr-archive-duplicate")
        try {
            val file = directory.resolve("image.png").apply { writeBytes(byteArrayOf(1)) }
            var failed = false
            try {
                LocalDataArchive.write(
                    output = ByteArrayOutputStream(),
                    data = byteArrayOf(1),
                    resources = listOf(
                        LocalDataArchive.ResourceFile("file:///same", "offline_content_images/a.png", file.toFile()),
                        LocalDataArchive.ResourceFile("file:///same", "offline_content_images/b.png", file.toFile())
                    )
                )
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun unsafeWindowsResourcePathIsRejected() {
        val directory = createTempDirectory("lnr-archive-path")
        try {
            val file = directory.resolve("image.png").apply { writeBytes(byteArrayOf(1)) }
            var failed = false
            try {
                LocalDataArchive.write(
                    output = ByteArrayOutputStream(),
                    data = byteArrayOf(1),
                    resources = listOf(
                        LocalDataArchive.ResourceFile(
                            "file:///image.png",
                            "offline_content_images\\..\\escape.png",
                            file.toFile()
                        )
                    )
                )
            } catch (_: IllegalArgumentException) {
                failed = true
            }
            assertTrue(failed)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun corruptedResourceIsRejected() {
        val sourceDirectory = createTempDirectory("lnr-archive-corrupt")
        val stagingDirectory = createTempDirectory("lnr-archive-corrupt-staging")
        try {
            val resource = sourceDirectory.resolve("image.jpg")
            resource.writeBytes(byteArrayOf(1, 2, 3))
            val output = ByteArrayOutputStream()
            LocalDataArchive.write(
                output,
                byteArrayOf(1),
                listOf(
                    LocalDataArchive.ResourceFile(
                        sourceUri = "file:///source/image.jpg",
                        targetPath = "offline_content_images/book/image.jpg",
                        file = resource.toFile()
                    )
                )
            )
            // 改变资源 ZIP 条目内容后，manifest 中的 SHA-256 必须使读取失败。
            val corruptedOutput = ByteArrayOutputStream()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { input ->
                ZipOutputStream(corruptedOutput).use { zip ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        val entryBytes = input.readBytes()
                        zip.putNextEntry(ZipEntry(entry.name))
                        if (entry.name.startsWith("resources/")) {
                            entryBytes[0] = (entryBytes[0].toInt() xor 0x01).toByte()
                        }
                        zip.write(entryBytes)
                        zip.closeEntry()
                    }
                }
            }
            var failed = false
            try {
                LocalDataArchive.read(
                    ByteArrayInputStream(corruptedOutput.toByteArray()),
                    stagingDirectory.toFile()
                )
            } catch (_: Throwable) {
                failed = true
            }
            assertTrue(failed)
        } finally {
            sourceDirectory.toFile().deleteRecursively()
            stagingDirectory.toFile().deleteRecursively()
        }
    }
}
