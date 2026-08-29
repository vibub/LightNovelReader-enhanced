package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.utils.ImageUtils
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将章节组件中的远程图片保存为应用私有文件，并把组件URI改写为本地URI。
 */
@Singleton
class OfflineContentCache @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class ChapterCacheResult(
        val content: ChapterContent,
        val imageCount: Int,
        val failedImageCount: Int
    ) {
        val isComplete: Boolean
            get() = failedImageCount == 0
    }

    private val rootDirectory: File
        get() = context.filesDir.resolve(ROOT_DIRECTORY)

    suspend fun cacheChapterContent(
        sourceId: Int,
        bookId: String,
        chapterContent: ChapterContent,
        header: Map<String, String>
    ): ChapterCacheResult = withContext(Dispatchers.IO) {
        val components = chapterContent.content["components"] as? JsonArray
            ?: return@withContext ChapterCacheResult(chapterContent, 0, 0)
        val imageCount = components.count { component ->
            component is JsonObject &&
                component["id"]?.jsonPrimitive?.content == ImageComponentData.id.toString()
        }
        if (imageCount == 0) {
            return@withContext ChapterCacheResult(chapterContent, 0, 0)
        }

        val targetDirectory = chapterDirectory(sourceId, bookId, chapterContent.id)
        val temporaryDirectory = targetDirectory.parentFile!!.resolve(
            ".${targetDirectory.name}.${UUID.randomUUID()}"
        )
        temporaryDirectory.mkdirs()

        var cachedImageCount = 0
        var failedImageCount = 0
        val rewrittenComponents = buildJsonArray {
            components.forEachIndexed { index, component ->
                val componentObject = component as? JsonObject
                val isImage = componentObject?.get("id")?.jsonPrimitive?.content ==
                    ImageComponentData.id.toString()
                if (!isImage) {
                    add(component)
                    return@forEachIndexed
                }

                val data = componentObject["data"] as? JsonObject
                val uriString = data?.get("uri")?.jsonPrimitive?.content
                val uri = uriString?.let(Uri::parse)
                if (uri == null || uriString.isBlank()) {
                    failedImageCount++
                    add(component)
                    return@forEachIndexed
                }

                val fileName = "$index-${sha256(uriString)}.jpg"
                val targetFile = targetDirectory.resolve(fileName)
                val temporaryFile = temporaryDirectory.resolve(fileName)
                val reusableContentUri = uri.scheme.equals("content", ignoreCase = true) ||
                    uri.scheme.equals("android.resource", ignoreCase = true)
                val localFile = uri.takeIf {
                    it.scheme.equals("file", ignoreCase = true)
                }?.path?.let(::File)?.takeIf { it.isFile }
                val cached = when {
                    localFile != null -> runCatching {
                        localFile.copyTo(temporaryFile, overwrite = true)
                    }.isSuccess
                    reusableContentUri -> true
                    targetFile.isFile && targetFile.length() > 0L -> {
                        runCatching {
                            targetFile.copyTo(temporaryFile, overwrite = true)
                        }.isSuccess
                    }
                    else -> {
                        val bitmap = try {
                            ImageUtils.uriToBitmap(uri, context, header).component1()
                        } catch (throwable: Throwable) {
                            if (throwable is CancellationException) throw throwable
                            null
                        }
                        bitmap?.let { writeBitmap(it, temporaryFile) } == true
                    }
                }
                if (!cached) {
                    failedImageCount++
                    add(component)
                    return@forEachIndexed
                }
                cachedImageCount++

                val localUri = if (reusableContentUri) uri else Uri.fromFile(targetFile)
                val newData = JsonObject(data + ("uri" to JsonPrimitive(localUri.toString())))
                add(JsonObject(componentObject + ("data" to newData)))
            }
        }

        if (cachedImageCount > 0) {
            replaceDirectory(temporaryDirectory, targetDirectory)
        } else {
            temporaryDirectory.deleteRecursively()
        }

        ChapterCacheResult(
            content = chapterContent.copy(
                content = JsonObject(chapterContent.content + ("components" to rewrittenComponents))
            ),
            imageCount = imageCount,
            failedImageCount = failedImageCount
        )
    }

    suspend fun cacheBookInformation(
        sourceId: Int,
        information: BookInformation,
        header: Map<String, String>
    ): BookInformation = withContext(Dispatchers.IO) {
        val sourceUri = information.coverUri
        if (!sourceUri.isRemoteImage()) return@withContext information

        val targetDirectory = bookDirectory(sourceId, information.id)
        val targetFile = targetDirectory.resolve(COVER_FILE_NAME)
        if (!targetFile.isFile || targetFile.length() <= 0L) {
            val bitmap = try {
                ImageUtils.uriToBitmap(sourceUri, context, header).component1()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                null
            } ?: return@withContext information
            if (!writeBitmap(bitmap, targetFile)) return@withContext information
        }
        information.copy(coverUri = Uri.fromFile(targetFile))
    }

    fun deleteChapterImages(sourceId: Int, bookId: String, chapterId: String) {
        chapterDirectory(sourceId, bookId, chapterId).deleteRecursively()
    }

    fun deleteBookImages(sourceId: Int, bookId: String) {
        bookDirectory(sourceId, bookId).deleteRecursively()
    }

    fun deleteAllImages() {
        rootDirectory.deleteRecursively()
    }

    private fun bookDirectory(sourceId: Int, bookId: String): File =
        rootDirectory.resolve(sourceId.toString()).resolve(sha256(bookId))

    private fun chapterDirectory(sourceId: Int, bookId: String, chapterId: String): File =
        bookDirectory(sourceId, bookId).resolve(sha256(chapterId))

    private fun writeBitmap(bitmap: Bitmap, file: File): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.outputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
        }
    }.isSuccess

    private fun replaceDirectory(temporaryDirectory: File, targetDirectory: File) {
        val backupDirectory = targetDirectory.parentFile!!.resolve(
            ".${targetDirectory.name}.old.${UUID.randomUUID()}"
        )
        val hadTarget = targetDirectory.exists()
        if (hadTarget) targetDirectory.renameTo(backupDirectory)
        if (!temporaryDirectory.renameTo(targetDirectory)) {
            temporaryDirectory.deleteRecursively()
            if (hadTarget) backupDirectory.renameTo(targetDirectory)
        }
        backupDirectory.deleteRecursively()
    }

    private fun Uri.isRemoteImage(): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ROOT_DIRECTORY = "offline_content_images"
        const val COVER_FILE_NAME = "cover.jpg"
        const val JPEG_QUALITY = 95
    }
}
