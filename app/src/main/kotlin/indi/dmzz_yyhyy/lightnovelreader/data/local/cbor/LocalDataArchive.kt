package indi.dmzz_yyhyy.lightnovelreader.data.local.cbor

import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .lnr 备份的 ZIP 容器读写工具。
 * data 条目始终排在第一个，以兼容旧版本只读取第一个条目的实现。
 */
object LocalDataArchive {
    const val DATA_ENTRY = "data"
    private const val MANIFEST_ENTRY = "manifest"
    private const val RESOURCE_PREFIX = "resources/"
    private const val MAX_ENTRY_SIZE = 512L * 1024L * 1024L

    data class ResourceFile(
        val sourceUri: String,
        val targetPath: String,
        val file: File
    )

    class ReadResult(
        val data: ByteArray,
        val manifest: LocalDataArchiveManifest?,
        val stagingDirectory: File
    )

    private val json = Json {
        ignoreUnknownKeys = true
    }

    fun write(
        output: OutputStream,
        data: ByteArray,
        resources: List<ResourceFile>
    ) {
        require(data.size.toLong() <= MAX_ENTRY_SIZE) { "数据条目过大" }
        require(resources.map { it.sourceUri }.distinct().size == resources.size) {
            "备份资源源 URI 重复"
        }
        require(resources.map { it.targetPath }.distinct().size == resources.size) {
            "备份资源目标路径重复"
        }
        val manifestResources = resources.map { resource ->
            require(isSafeTargetPath(resource.targetPath)) {
                "不安全的资源路径：${resource.targetPath}"
            }
            require(resource.file.isFile) { "资源文件不存在：${resource.file}" }
            val size = resource.file.length()
            require(size <= MAX_ENTRY_SIZE) { "资源条目过大：${resource.targetPath}" }
            val digest = resource.file.sha256()
            val extension = resource.targetPath.substringAfterLast('.', "")
                .lowercase()
                .takeIf(String::isNotBlank)
                ?: "bin"
            LocalDataArchiveManifest.Resource(
                sourceUri = resource.sourceUri,
                entryName = "$RESOURCE_PREFIX${sha256(resource.sourceUri + resource.targetPath)}.$extension",
                targetPath = resource.targetPath,
                sha256 = digest,
                size = size,
                mediaType = mediaType(extension),
                extension = extension
            )
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(data)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(
                json.encodeToString(
                    LocalDataArchiveManifest.serializer(),
                    LocalDataArchiveManifest(resources = manifestResources)
                ).encodeToByteArray()
            )
            zip.closeEntry()

            resources.zip(manifestResources).forEach { (resource, manifest) ->
                zip.putNextEntry(ZipEntry(manifest.entryName))
                resource.file.inputStream().use { input ->
                    input.copyToLimited(zip, MAX_ENTRY_SIZE)
                }
                zip.closeEntry()
            }
        }
    }

    /**
     * 读取备份并将资源暂存到 stagingDirectory。调用方应在导入成功或失败后删除该目录。
     */
    fun read(
        input: InputStream,
        stagingDirectory: File = File.createTempFile(
            "lnr-import-",
            "",
            null
        ).apply {
            delete()
            mkdirs()
        }
    ): ReadResult {
        val bufferedInput = if (input.markSupported()) input else BufferedInputStream(input)
        bufferedInput.mark(4)
        val header = ByteArray(4)
        val headerSize = bufferedInput.read(header)
        bufferedInput.reset()
        if (!header.isZipHeader(headerSize)) {
            return ReadResult(
                data = bufferedInput.readBytesLimited(MAX_ENTRY_SIZE),
                manifest = null,
                stagingDirectory = stagingDirectory
            )
        }
        return readZip(bufferedInput, stagingDirectory)
    }

    private fun readZip(
        input: InputStream,
        stagingDirectory: File
    ): ReadResult {
        var data: ByteArray? = null
        var manifest: LocalDataArchiveManifest? = null
        val pendingResources = mutableMapOf<String, File>()
        val seenEntries = mutableSetOf<String>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (entry.name.contains('\\') || entry.name.startsWith('/') ||
                    entry.name.split('/').any { it == ".." }
                ) {
                    throw IOException("备份条目路径不安全：${entry.name}")
                }
                if (!seenEntries.add(entry.name)) {
                    throw IOException("备份中存在重复条目：${entry.name}")
                }
                if (entry.size > MAX_ENTRY_SIZE) {
                    throw IOException("备份条目过大：${entry.name}")
                }
                when (entry.name) {
                    DATA_ENTRY -> data = zip.readBytesLimited(MAX_ENTRY_SIZE)
                    MANIFEST_ENTRY -> {
                        manifest = json.decodeFromString<LocalDataArchiveManifest>(
                            zip.readBytesLimited(MAX_ENTRY_SIZE).decodeToString()
                        )
                    }
                    else -> {
                        if (entry.name.startsWith(RESOURCE_PREFIX)) {
                            val file = stagingDirectory.resolve(UUID.randomUUID().toString())
                            file.outputStream().use { output -> zip.copyToAndHash(output) }
                            pendingResources[entry.name] = file
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        val actualManifest = manifest
        if (actualManifest != null) {
            if (actualManifest.archiveVersion != LocalDataArchiveManifest.CURRENT_ARCHIVE_VERSION) {
                throw IOException("不支持的备份资源版本：${actualManifest.archiveVersion}")
            }
            val expectedEntries = actualManifest.resources.associateBy { it.entryName }
            if (expectedEntries.size != actualManifest.resources.size ||
                actualManifest.resources.map { it.targetPath }.distinct().size !=
                    actualManifest.resources.size ||
                actualManifest.resources.map { it.sourceUri }.distinct().size !=
                    actualManifest.resources.size
            ) {
                throw IOException("备份资源清单存在重复条目")
            }
            actualManifest.resources.forEach { resource ->
                if (!resource.entryName.startsWith(RESOURCE_PREFIX) ||
                    !isSafeTargetPath(resource.targetPath) ||
                    resource.size < 0L ||
                    resource.sha256.length != 64 ||
                    resource.sha256.any { it !in "0123456789abcdefABCDEF" }
                ) {
                    throw IOException("备份资源清单包含无效条目：${resource.entryName}")
                }
            }
            pendingResources.forEach { (entryName, stagedFile) ->
                val resource = expectedEntries[entryName]
                    ?: throw IOException("资源未在清单中声明：$entryName")
                if (stagedFile.length() != resource.size || stagedFile.sha256() != resource.sha256) {
                    throw IOException("资源校验失败：$entryName")
                }
                if (!isSafeTargetPath(resource.targetPath)) {
                    throw IOException("不安全的资源路径：${resource.targetPath}")
                }
                val target = stagingDirectory.resolve(resource.targetPath)
                target.parentFile?.mkdirs()
                if (!stagedFile.renameTo(target)) {
                    stagedFile.copyTo(target, overwrite = true)
                    stagedFile.delete()
                }
            }
            val declaredNames = expectedEntries.keys
            if (pendingResources.keys != declaredNames) {
                throw IOException("备份缺少资源文件")
            }
        } else {
            pendingResources.values.forEach(File::delete)
        }

        return ReadResult(
            data = data ?: throw IOException("备份中缺少 data 条目"),
            manifest = actualManifest,
            stagingDirectory = stagingDirectory
        )
    }

    private fun ByteArray.isZipHeader(size: Int): Boolean = size >= 4 &&
        this[0] == 'P'.code.toByte() &&
        this[1] == 'K'.code.toByte() &&
        (this[2] == 0x03.toByte() && this[3] == 0x04.toByte() ||
            this[2] == 0x05.toByte() && this[3] == 0x06.toByte() ||
            this[2] == 0x07.toByte() && this[3] == 0x08.toByte())

    private fun isSafeTargetPath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            path.split('/').none { it == ".." } &&
            (path.startsWith("offline_content_images/") || path in USER_RESOURCE_NAMES)

    private fun mediaType(name: String): String = when (
        name.substringAfterLast('.', name).lowercase()
    ) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg", "svgz" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        else -> "application/octet-stream"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun InputStream.readBytesLimited(maxSize: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyToAndHash(output, maxSize)
        return output.toByteArray()
    }

    private fun InputStream.copyToLimited(
        output: OutputStream,
        maxSize: Long
    ) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxSize) throw IOException("备份条目过大")
            output.write(buffer, 0, count)
        }
    }

    private fun InputStream.copyToAndHash(
        output: OutputStream,
        maxSize: Long = MAX_ENTRY_SIZE
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maxSize) throw IOException("备份条目过大")
            output.write(buffer, 0, count)
        }
    }

    private val USER_RESOURCE_NAMES = setOf(
        "readerTextFont",
        "readerBackgroundImage",
        "readerDarkBackgroundImage"
    )
}
