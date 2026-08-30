package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
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
 * 将章节组件中的图片保存为应用私有文件，并把组件 URI 改写为本地 URI。
 */
@Singleton
class OfflineContentCache @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class ChapterCacheResult(
        val content: ChapterContent,
        val imageCount: Int,
        val failedImageCount: Int,
        val bytesWritten: Long = 0L
    ) {
        val isComplete: Boolean
            get() = failedImageCount == 0
    }

    data class CachedFile(
        val relativePath: String,
        val file: File
    )

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
        try {
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
                        ?.takeIf(String::isNotBlank)
                    if (data == null || uriString == null) {
                        failedImageCount++
                        add(component)
                        return@forEachIndexed
                    }
                    val uri = uriString.toUri()

                    val baseName = "$index-${sha256(uriString)}"
                    val existing = targetDirectory.listFiles()
                        ?.firstOrNull { file ->
                            file.isFile && file.length() > 0L &&
                                !file.name.endsWith(".download", ignoreCase = true) &&
                                file.nameWithoutExtension == baseName
                        }
                    val targetName = try {
                        if (existing != null) {
                            existing.copyTo(
                                temporaryDirectory.resolve(existing.name),
                                overwrite = true
                            )
                            existing.name
                        } else {
                            val temporaryFile = temporaryDirectory.resolve("$baseName.download")
                            val format = ImageUtils.copyEncodedImage(
                                imageUri = uri,
                                context = context,
                                targetFile = temporaryFile,
                                header = header
                            ).component1() ?: throw IllegalStateException("无法保存图片")
                            val finalName = "$baseName.${format.extension}"
                            check(temporaryFile.renameTo(temporaryDirectory.resolve(finalName))) {
                                "无法移动图片缓存文件"
                            }
                            finalName
                        }
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) throw throwable
                        null
                    }
                    if (targetName == null) {
                        failedImageCount++
                        add(component)
                        return@forEachIndexed
                    }

                    cachedImageCount++
                    val localUri = Uri.fromFile(targetDirectory.resolve(targetName))
                    val newData = JsonObject(
                        data + ("uri" to JsonPrimitive(localUri.toString()))
                    )
                    add(JsonObject(componentObject + ("data" to newData)))
                }
            }

            val replaced = if (cachedImageCount > 0) {
                replaceDirectory(temporaryDirectory, targetDirectory)
            } else {
                temporaryDirectory.deleteRecursively()
                true
            }
            if (!replaced) {
                return@withContext ChapterCacheResult(
                    content = chapterContent,
                    imageCount = imageCount,
                    failedImageCount = imageCount,
                    bytesWritten = directoryBytes(targetDirectory)
                )
            }

            return@withContext ChapterCacheResult(
                content = chapterContent.copy(
                    content = JsonObject(
                        chapterContent.content + ("components" to rewrittenComponents)
                    )
                ),
                imageCount = imageCount,
                failedImageCount = failedImageCount,
                bytesWritten = directoryBytes(targetDirectory)
            )
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    suspend fun cacheBookInformation(
        sourceId: Int,
        information: BookInformation,
        header: Map<String, String>
    ): BookInformation = withContext(Dispatchers.IO) {
        val sourceUri = information.coverUri
        if (sourceUri == Uri.EMPTY || sourceUri.toString().isBlank()) {
            return@withContext information
        }

        val targetDirectory = bookDirectory(sourceId, information.id).apply { mkdirs() }
        val existing = targetDirectory.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    !it.name.endsWith(".download", ignoreCase = true) &&
                    it.nameWithoutExtension == COVER_FILE_BASENAME &&
                    it.length() > 0L
            }
        val targetFile = if (existing != null) {
            existing
        } else {
            val temporaryFile = targetDirectory.resolve("$COVER_FILE_BASENAME.download")
            val format = try {
                ImageUtils.copyEncodedImage(
                    imageUri = sourceUri,
                    context = context,
                    targetFile = temporaryFile,
                    header = header
                ).component1() ?: return@withContext information
            } catch (throwable: Throwable) {
                temporaryFile.delete()
                if (throwable is CancellationException) throw throwable
                return@withContext information
            }
            val finalFile = targetDirectory.resolve("$COVER_FILE_BASENAME.${format.extension}")
            if (!temporaryFile.renameTo(finalFile)) {
                temporaryFile.delete()
                return@withContext information
            }
            targetDirectory.listFiles()
                ?.filter { it.isFile && it.nameWithoutExtension == COVER_FILE_BASENAME && it != finalFile }
                ?.forEach(File::delete)
            finalFile
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

    /** 删除阅读器自定义资源，供导入失败回滚时恢复旧资源集合。 */
    fun deleteUserResources() {
        USER_RESOURCE_NAMES.forEach { name ->
            context.filesDir.resolve(name).deleteRecursively()
        }
    }

    /** 返回可放入本地数据备份的图片及阅读器自定义资源。 */
    fun exportableFiles(): Sequence<CachedFile> = sequence {
        rootDirectory.walkTopDown()
            .filter(File::isFile)
            .filterNot { it.name.startsWith(".") || it.name.endsWith(".download") }
            .forEach { file ->
                yield(CachedFile(relativePath(file), file))
            }
        USER_RESOURCE_NAMES.forEach { name ->
            val file = context.filesDir.resolve(name)
            if (file.isFile && file.length() > 0L) yield(CachedFile(name, file))
        }
    }

    /** 将备份暂存目录中的资源安全、原子地复制到当前应用的 filesDir。 */
    suspend fun restoreFiles(stagingDirectory: File): Boolean = withContext(Dispatchers.IO) {
        if (!stagingDirectory.isDirectory) return@withContext true

        data class RestoreFile(
            val source: File,
            val relativePath: String,
            val destination: File,
            val prepared: File,
            val backup: File?
        )

        val restoreDirectory = context.cacheDir.resolve(".lnr-restore-${UUID.randomUUID()}")
        val backupDirectory = context.cacheDir.resolve(".lnr-restore-backup-${UUID.randomUUID()}")
        val restoreFiles = mutableListOf<RestoreFile>()
        val committedFiles = mutableListOf<RestoreFile>()
        try {
            var valid = true
            stagingDirectory.walkTopDown()
                .filter(File::isFile)
                .forEach { source ->
                    val relative = relativePath(stagingDirectory, source)
                    if (relative == null || !isAllowedResourcePath(relative)) {
                        valid = false
                        return@forEach
                    }
                    val destination = context.filesDir.resolve(relative)
                    restoreFiles += RestoreFile(
                        source = source,
                        relativePath = relative,
                        destination = destination,
                        prepared = restoreDirectory.resolve(relative),
                        backup = backupDirectory.resolve(relative)
                    )
                }
            if (!valid) return@withContext false

            // 先在 filesDir 之外准备完整副本，任何一个资源失败都不会留下半套文件。
            restoreFiles.forEach { file ->
                file.prepared.parentFile?.mkdirs()
                file.source.copyTo(file.prepared, overwrite = true)
            }
            restoreFiles.forEach { file ->
                if (file.destination.exists() && !file.destination.isFile) {
                    throw IllegalStateException("资源目标不是文件：${file.relativePath}")
                }
                val hadDestination = file.destination.isFile
                if (hadDestination) {
                    val backup = file.backup ?: error("缺少资源备份路径")
                    backup.parentFile?.mkdirs()
                    file.destination.copyTo(backup, overwrite = true)
                }
                file.destination.parentFile?.mkdirs()
                committedFiles += file.copy(
                    backup = file.backup.takeIf { hadDestination }
                )
                file.prepared.copyTo(file.destination, overwrite = true)
            }
            true
        } catch (throwable: Throwable) {
            committedFiles.asReversed().forEach { file ->
                runCatching {
                    file.backup
                        ?.takeIf(File::isFile)
                        ?.copyTo(file.destination, overwrite = true)
                        ?: file.destination.delete()
                }
            }
            if (throwable is CancellationException) throw throwable
            false
        } finally {
            restoreDirectory.deleteRecursively()
            backupDirectory.deleteRecursively()
        }
    }

    fun rewriteImportedChapterContent(
        content: JsonObject,
        resourceUriMap: Map<String, String> = emptyMap()
    ): JsonObject {
        val components = content["components"] as? JsonArray ?: return content
        val rewritten = buildJsonArray {
            components.forEach { component ->
                val componentObject = component as? JsonObject
                val data = componentObject?.get("data") as? JsonObject
                val isImage = componentObject?.get("id")?.jsonPrimitive?.content ==
                    ImageComponentData.id.toString()
                val oldUri = data?.get("uri")?.jsonPrimitive?.content?.let(Uri::parse)
                if (!isImage || data == null || oldUri == null) {
                    add(component)
                } else {
                    add(
                        JsonObject(
                            componentObject + (
                                "data" to JsonObject(
                                    data + (
                                    "uri" to JsonPrimitive(
                                        rewriteImportedUri(oldUri, resourceUriMap).toString()
                                    )
                                )
                                )
                            )
                        )
                    )
                }
            }
        }
        return JsonObject(content + ("components" to rewritten))
    }

    fun rewriteImportedUri(uri: Uri, resourceUriMap: Map<String, String> = emptyMap()): Uri {
        resourceUriMap[uri.toString()]?.let(Uri::parse)?.let { return it }
        if (!uri.scheme.equals("file", ignoreCase = true)) return uri
        val path = uri.path ?: return uri
        val marker = "$ROOT_DIRECTORY/"
        val rootIndex = path.indexOf(marker)
        if (rootIndex >= 0) {
            return Uri.fromFile(context.filesDir.resolve(path.substring(rootIndex)))
        }
        USER_RESOURCE_NAMES.firstOrNull { name -> path.endsWith("/$name") }
            ?.let { return Uri.fromFile(context.filesDir.resolve(it)) }
        return uri
    }

    private fun bookDirectory(sourceId: Int, bookId: String): File =
        rootDirectory.resolve(sourceId.toString()).resolve(sha256(bookId))

    private fun chapterDirectory(sourceId: Int, bookId: String, chapterId: String): File =
        bookDirectory(sourceId, bookId).resolve(sha256(chapterId))

    /** 原子替换章节图片目录；替换失败时保留原目录。 */
    private fun replaceDirectory(temporaryDirectory: File, targetDirectory: File): Boolean {
        val backupDirectory = targetDirectory.parentFile!!.resolve(
            ".${targetDirectory.name}.old.${UUID.randomUUID()}"
        )
        val hadTarget = targetDirectory.exists()
        if (hadTarget && !targetDirectory.renameTo(backupDirectory)) {
            temporaryDirectory.deleteRecursively()
            return false
        }
        if (!temporaryDirectory.renameTo(targetDirectory)) {
            temporaryDirectory.deleteRecursively()
            if (hadTarget && !backupDirectory.renameTo(targetDirectory)) {
                backupDirectory.deleteRecursively()
            }
            return false
        }
        backupDirectory.deleteRecursively()
        return true
    }

    private fun relativePath(file: File): String =
        relativePath(context.filesDir, file) ?: file.name

    private fun relativePath(root: File, file: File): String? {
        val rootPath = root.absoluteFile.path.trimEnd(File.separatorChar)
        val filePath = file.absoluteFile.path
        if (filePath == rootPath) return ""
        val prefix = "$rootPath${File.separatorChar}"
        return filePath.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.replace(File.separatorChar, '/')
    }

    private fun directoryBytes(directory: File): Long =
        if (!directory.isDirectory) 0L
        else directory.walkTopDown().filter(File::isFile).sumOf { it.length() }

    private fun isAllowedResourcePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it == ".." }
        ) {
            return false
        }
        return path.startsWith("$ROOT_DIRECTORY/") || path in USER_RESOURCE_NAMES
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ROOT_DIRECTORY = "offline_content_images"
        const val COVER_FILE_BASENAME = "cover"
        val USER_RESOURCE_NAMES = setOf(
            "readerTextFont",
            "readerBackgroundImage",
            "readerDarkBackgroundImage"
        )
    }
}
