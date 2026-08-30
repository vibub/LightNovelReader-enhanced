package indi.dmzz_yyhyy.lightnovelreader.data.local

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.util.Log
import androidx.room.withTransaction
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.asErr
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.localDataCbor
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalDataArchive
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookInformationDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookRecordDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookVolumesDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.BookshelfDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterContentDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.ChapterDownloadDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DownloadTaskDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.DailyCountDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.FormattingRuleDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.LightNovelReaderDatabase
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserReadingDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.BookInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.ChapterInformationEntity
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.Mergeable
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.VolumeEntity
import indi.dmzz_yyhyy.lightnovelreader.data.storage.StorageUsageRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.ImageUtils
import indi.dmzz_yyhyy.lightnovelreader.utils.readAppLocalData
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("OPT_IN_USAGE")
@Singleton
class LocalDataManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: LightNovelReaderDatabase,
    private val webDataSourceProvider: WebBookDataSourceProvider,
    private val bookBookInformationDao: BookInformationDao,
    private val bookRecordDao: BookRecordDao,
    private val dailyCountDao: DailyCountDao,
    private val bookshelfDao: BookshelfDao,
    private val chapterContentDao: ChapterContentDao,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val downloadTaskDao: DownloadTaskDao,
    private val bookVolumesDao: BookVolumesDao,
    private val formattingRuleDao: FormattingRuleDao,
    private val userReadingDataDao: UserReadingDataDao,
    private val userDataDao: UserDataDao,
    private val storageUsageRepository: StorageUsageRepository,
    private val offlineContentCache: OfflineContentCache
) {
    companion object {
        const val TAG = "LocalDataManager"
    }

    val currentAppDataVersion = 0
    val localDataDir = context.dataDir.resolve("local_data").also {
        if (!it.exists()) it.mkdirs()
    }
    val webDataSourceUserDataPathSet = mutableSetOf<String>()

    private data class ImportBackup(
        val appLocalData: AppLocalData,
        val resourceDirectory: File,
        val localDataDirectory: File
    ) {
        val rootDirectory: File
            get() = resourceDirectory.parentFile ?: localDataDirectory.parentFile
            ?: error("导入备份目录不存在")
    }

    fun registerWebDataSourceUserData(path: String) {
        webDataSourceUserDataPathSet.add(path)
    }

    suspend fun exportAppLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true
    ): Result<AppLocalData, Throwable> {
        val localDataList = mutableListOf<LocalData>()
        localDataDir.listFiles()?.forEach {
            it.inputStream().use { inputStream ->
                localDataCbor.decodeFromByteArray<LocalData>(inputStream.readAppLocalData())
            }.let(localDataList::add)
        }
        exportCurrentLocalData(
            localBookCache, bookshelf, readingRecord, settings
        ).let {
            it.component1() ?: return it.asErr()
        }.let(localDataList::add)
        val globalLocalData = LocalData.empty()
            .copy(userDataEntities = if (settings) userDataDao.getAllEntities().filter {
                !webDataSourceUserDataPathSet.contains(it.path) &&
                        it.path != UserDataPath.Settings.Data.StorageUsageSnapshot.path
            }
            else emptyList())
        return Ok(
            AppLocalData(
                version = currentAppDataVersion,
                localDataList = localDataList,
                globalLocalData = globalLocalData
            )
        )
    }

    fun exportableResources(): List<LocalDataArchive.ResourceFile> =
        offlineContentCache.exportableFiles().map { resource ->
            LocalDataArchive.ResourceFile(
                sourceUri = Uri.fromFile(resource.file).toString(),
                targetPath = resource.relativePath,
                file = resource.file
            )
        }.toList()

    /**
     * 在已有缓存之外补齐备份数据实际引用的外部 file/content URI，避免导入到另一台设备后图片和字体失效。
     * 临时文件由 [cleanupTemporaryExportResources] 在归档写入后删除。
     */
    suspend fun exportableResources(
        appLocalData: AppLocalData
    ): List<LocalDataArchive.ResourceFile> = withContext(Dispatchers.IO) {
        val resources = exportableResources().toMutableList()
        val existingFiles = resources.map { it.file.canonicalFile }.toSet()
        val temporaryDirectory = context.cacheDir.resolve("lnr-export-${UUID.randomUUID()}")
        val referencedUris = referencedResourceUris(appLocalData)
        for (uri in referencedUris) {
            if (uri == Uri.EMPTY || uri.toString().isBlank()) continue
            if (uri.scheme.equals("file", ignoreCase = true)) {
                val file = uri.path?.let(::File)
                if (file?.isFile == true && file.canonicalFile in existingFiles) continue
            }
            materializeExternalResource(uri, temporaryDirectory)?.let(resources::add)
        }
        resources
    }

    fun cleanupTemporaryExportResources(resources: List<LocalDataArchive.ResourceFile>) {
        resources.asSequence()
            .map { it.file.parentFile }
            .filterNotNull()
            .filter { it.name.startsWith("lnr-export-") }
            .distinct()
            .forEach(File::deleteRecursively)
    }

    private fun referencedResourceUris(appLocalData: AppLocalData): List<Uri> = buildList {
        (appLocalData.localDataList + appLocalData.globalLocalData).forEach { localData ->
            localData.bookInformationEntities.forEach { add(it.coverUri) }
            localData.chapterContentEntities.forEach { entity ->
                (entity.content["components"] as? JsonArray)?.forEach { component ->
                    val componentObject = component as? JsonObject ?: return@forEach
                    if (componentObject["id"]?.jsonPrimitive?.content != ImageComponentData.id.toString()) {
                        return@forEach
                    }
                    val uri = (componentObject["data"] as? JsonObject)
                        ?.get("uri")
                        ?.jsonPrimitive
                        ?.content
                        ?.takeIf(String::isNotBlank)
                        ?.let(Uri::parse)
                    if (uri != null) add(uri)
                }
            }
            localData.userDataEntities
                .filter { it.type == "Uri" }
                .mapNotNull { it.value.takeIf(String::isNotBlank)?.let(Uri::parse) }
                .forEach(::add)
        }
    }.distinctBy(Uri::toString)

    private suspend fun materializeExternalResource(
        uri: Uri,
        temporaryDirectory: File
    ): LocalDataArchive.ResourceFile? {
        temporaryDirectory.mkdirs()
        val baseName = sha256(uri.toString())
        val encodedTarget = temporaryDirectory.resolve("$baseName.encoded")
        val encodedFormat = ImageUtils.copyEncodedImage(
            imageUri = uri,
            context = context,
            targetFile = encodedTarget
        ).component1()
        if (encodedFormat != null) {
            val targetFile = temporaryDirectory.resolve("$baseName.${encodedFormat.extension}")
            if (!encodedTarget.renameTo(targetFile)) {
                encodedTarget.copyTo(targetFile, overwrite = true)
                encodedTarget.delete()
            }
            return LocalDataArchive.ResourceFile(
                sourceUri = uri.toString(),
                targetPath = "offline_content_images/imported/${targetFile.name}",
                file = targetFile
            )
        }

        val input = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.inputStream()
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)
            else -> null
        } ?: return null
        val extension = resourceExtension(uri)
        val targetFile = temporaryDirectory.resolve("$baseName.$extension")
        return try {
            input.use { source ->
                targetFile.outputStream().use { destination -> source.copyTo(destination) }
            }
            if (targetFile.length() <= 0L) {
                targetFile.delete()
                null
            } else {
                LocalDataArchive.ResourceFile(
                    sourceUri = uri.toString(),
                    targetPath = "offline_content_images/imported/${targetFile.name}",
                    file = targetFile
                )
            }
        } catch (throwable: Throwable) {
            targetFile.delete()
            if (throwable is CancellationException) throw throwable
            null
        }
    }

    private fun resourceExtension(uri: Uri): String {
        val mimeType = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "content", "android.resource" -> context.contentResolver.getType(uri)
            else -> null
        }
        return when (mimeType?.substringAfter('/', "")?.lowercase(Locale.ROOT)) {
            "png", "gif", "jpeg", "jpg", "webp", "bmp", "svg+xml", "ttf", "otf" ->
                mimeType.substringAfter('/').substringBefore('+')
            else -> uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: "bin"
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun exportCurrentLocalData(
        localBookCache: Boolean = true,
        bookshelf: Boolean = true,
        readingRecord: Boolean = true,
        settings: Boolean = true,
        sourceId: Int? = null
    ): Result<LocalData, Throwable> {
        val exportOptionLocalData = ExportOptionLocalData(
            bookBookInformationDao = bookBookInformationDao,
            bookRecordDao = bookRecordDao,
            dailyCountDao = dailyCountDao,
            bookshelfDao = bookshelfDao,
            chapterContentDao = chapterContentDao,
            chapterDownloadDao = chapterDownloadDao,
            downloadTaskDao = downloadTaskDao,
            bookVolumesDao = bookVolumesDao,
            formattingRuleDao = formattingRuleDao,
            userReadingDataDao = userReadingDataDao,
            userDataDao = userDataDao,
            webDataSourceUserDataPathSet = webDataSourceUserDataPathSet,
            sourceId = sourceId
        ).apply {
            this.localBookCache.enable = localBookCache
            this.bookshelf.enable = bookshelf
            this.readingRecord.enable = readingRecord
            this.settings.enable = settings
        }

        return runCatching {
            exportOptionLocalData.solve()
        }.andThen {
            Ok(
                LocalData(
                    webBookDataSourceId = webDataSourceProvider.value.id,
                    bookInformationEntities = exportOptionLocalData.bookInformationEntities,
                    bookRecordEntities = exportOptionLocalData.bookRecordEntities,
                    dailyCountEntities = exportOptionLocalData.dailyCountEntities,
                    bookshelfEntities = exportOptionLocalData.bookshelfEntities,
                    bookshelfBookMetadataEntities = exportOptionLocalData.bookshelfBookMetadataEntities,
                    chapterContentEntities = exportOptionLocalData.chapterContentEntities,
                    chapterInformationEntities = exportOptionLocalData.chapterInformationEntities,
                    chapterDownloadEntities = exportOptionLocalData.chapterDownloadEntities,
                    downloadTaskEntities = exportOptionLocalData.downloadTaskEntities,
                    formattingRuleEntities = exportOptionLocalData.formattingRuleEntities,
                    userReadingDataEntities = exportOptionLocalData.userReadingDataEntities,
                    volumeEntities = exportOptionLocalData.volumeEntities,
                    userDataEntities = exportOptionLocalData.userDataEntities
                ).withResolvedSourceId(sourceId)
            )
        }
    }

    private fun LocalData.withResolvedSourceId(fallbackSourceId: Int? = null): LocalData {
        val resolvedSourceId = fallbackSourceId
            ?: webBookDataSourceId?.toLegacyCompatibleSourceId()
            ?: BookInformationEntity.LEGACY_SOURCE_ID
        return copy(
            bookInformationEntities = bookInformationEntities.resolveLegacySource(
                resolvedSourceId = resolvedSourceId,
                legacySourceId = BookInformationEntity.LEGACY_SOURCE_ID,
                key = { it.id },
                sourceId = { it.sourceId },
                copyWithSourceId = { entity, id -> entity.copy(sourceId = id) }
            ),
            chapterInformationEntities = chapterInformationEntities.resolveLegacySource(
                resolvedSourceId = resolvedSourceId,
                legacySourceId = ChapterInformationEntity.LEGACY_SOURCE_ID,
                key = { it.bookId to it.id },
                sourceId = { it.sourceId },
                copyWithSourceId = { entity, id -> entity.copy(sourceId = id) }
            ),
            volumeEntities = volumeEntities.resolveLegacySource(
                resolvedSourceId = resolvedSourceId,
                legacySourceId = VolumeEntity.LEGACY_SOURCE_ID,
                key = { it.bookId to it.volumeId },
                sourceId = { it.sourceId },
                copyWithSourceId = { entity, id -> entity.copy(sourceId = id) }
            )
        )
    }

    private fun <T, K> List<T>.resolveLegacySource(
        resolvedSourceId: Int,
        legacySourceId: Int,
        key: (T) -> K,
        sourceId: (T) -> Int,
        copyWithSourceId: (T, Int) -> T
    ): List<T> {
        if (resolvedSourceId == legacySourceId) return this
        val exactKeys = filter { sourceId(it) == resolvedSourceId }
            .mapTo(mutableSetOf(), key)
        return mapNotNull { entity ->
            when {
                sourceId(entity) != legacySourceId -> entity
                key(entity) in exactKeys -> null
                else -> copyWithSourceId(entity, resolvedSourceId)
            }
        }
    }

    private suspend fun createImportBackup(): ImportBackup {
        val rootDirectory = context.cacheDir.resolve("lnr-import-backup-${UUID.randomUUID()}")
        val resourceDirectory = rootDirectory.resolve("resources")
        val localDataDirectory = rootDirectory.resolve("local_data")
        try {
            val appLocalData = exportAppLocalData(
                localBookCache = true,
                bookshelf = true,
                readingRecord = true,
                settings = true
            ).component1() ?: throw Error("无法备份当前本地数据")
            resourceDirectory.mkdirs()
            localDataDirectory.mkdirs()
            offlineContentCache.exportableFiles().forEach { resource ->
                require(
                    resource.relativePath.isNotBlank() &&
                        !resource.relativePath.startsWith('/') &&
                        !resource.relativePath.contains('\\') &&
                        resource.relativePath.split('/').none { it == ".." }
                ) {
                    "无法备份不安全的资源路径：${resource.relativePath}"
                }
                val destination = resourceDirectory.resolve(resource.relativePath)
                destination.parentFile?.mkdirs()
                resource.file.copyTo(destination, overwrite = false)
            }
            localDataDir.listFiles()
                ?.filter(File::isFile)
                ?.forEach { source ->
                    source.copyTo(localDataDirectory.resolve(source.name), overwrite = false)
                }
            return ImportBackup(appLocalData, resourceDirectory, localDataDirectory)
        } catch (throwable: Throwable) {
            rootDirectory.deleteRecursively()
            throw throwable
        }
    }

    private suspend fun clearDatabaseForImportRestore() {
        database.withTransaction {
            bookBookInformationDao.clear()
            bookRecordDao.clear()
            dailyCountDao.clear()
            bookshelfDao.clear()
            bookVolumesDao.clear()
            chapterContentDao.clear()
            chapterDownloadDao.clear()
            downloadTaskDao.clear()
            formattingRuleDao.clear()
            userReadingDataDao.clear()
            userDataDao.getAllEntities().forEach { userDataDao.remove(it.path) }
        }
        offlineContentCache.deleteAllImages()
        offlineContentCache.deleteUserResources()
    }

    private suspend fun restoreImportBackup(backup: ImportBackup) {
        clearDatabaseForImportRestore()
        deleteLocalDataFiles()
        importAppLocalData(
            appLocalData = backup.appLocalData,
            stagingDirectory = backup.resourceDirectory
        ).onErr { throw it }
        restoreLocalDataFiles(backup.localDataDirectory)
        storageUsageRepository.invalidateSnapshot()
    }

    private fun deleteLocalDataFiles() {
        localDataDir.listFiles()?.forEach(File::deleteRecursively)
    }

    private fun restoreLocalDataFiles(backupDirectory: File) {
        deleteLocalDataFiles()
        localDataDir.mkdirs()
        backupDirectory.listFiles()
            ?.filter(File::isFile)
            ?.forEach { source ->
                source.copyTo(localDataDir.resolve(source.name), overwrite = false)
            }
    }

    suspend fun importAppLocalData(
        appLocalData: AppLocalData,
        stagingDirectory: File? = null,
        resourceUriMap: Map<String, String> = emptyMap(),
        overwrite: Boolean = false
    ): Result<Unit, Throwable> {
        if (currentAppDataVersion != appLocalData.version) {
            Log.e(TAG, "Unsupported data versions")
            return Err(Error("Unsupported data versions"))
        }

        val importBackup = if (overwrite) {
            try {
                createImportBackup()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                return Err(throwable)
            }
        } else {
            null
        }

        try {
            if (overwrite) {
                cleanDatabaseWithoutGlobalUserData(deleteOfflineImages = true)
            }
            if (stagingDirectory != null && !offlineContentCache.restoreFiles(stagingDirectory)) {
                throw Error("无法恢复备份中的资源文件")
            }
            val rewrittenData = rewriteImportedData(appLocalData, resourceUriMap)
            importAppLocalDataToDestinations(rewrittenData)
            storageUsageRepository.invalidateSnapshot()
            return Ok(Unit)
        } catch (throwable: Throwable) {
            importBackup?.let { backup ->
                try {
                    withContext(NonCancellable) {
                        restoreImportBackup(backup)
                    }
                } catch (restoreError: Throwable) {
                    Log.e(TAG, "导入失败后无法完整恢复原有数据", restoreError)
                }
            }
            if (throwable is CancellationException) throw throwable
            return Err(throwable)
        } finally {
            importBackup?.rootDirectory?.deleteRecursively()
        }
    }

    /** 当前数据源的数据进入数据库，其他数据源继续保存在独立的本地数据文件中。 */
    private suspend fun importAppLocalDataToDestinations(appLocalData: AppLocalData) {
        val currentSourceId = webDataSourceProvider.value.id
        database.withTransaction {
            importLocalDataToDatabase(appLocalData.globalLocalData).onErr { throw it }
            appLocalData.localDataList
                .filter { it.webBookDataSourceId == currentSourceId }
                .forEach { localData ->
                    importLocalDataToDatabase(localData).onErr { throw it }
                }
        }
        appLocalData.localDataList
            .filterNot { it.webBookDataSourceId == currentSourceId }
            .forEach { localData ->
                importLocalDataToFile(localData).onErr { throw it }
            }
    }

    private fun rewriteImportedData(
        appLocalData: AppLocalData,
        resourceUriMap: Map<String, String>
    ): AppLocalData = appLocalData.copy(
        localDataList = appLocalData.localDataList.map {
            rewriteImportedLocalData(it, resourceUriMap)
        },
        globalLocalData = rewriteImportedLocalData(appLocalData.globalLocalData, resourceUriMap)
    )

    private fun rewriteImportedLocalData(
        localData: LocalData,
        resourceUriMap: Map<String, String>
    ): LocalData = localData.copy(
        bookInformationEntities = localData.bookInformationEntities.map { entity ->
            entity.copy(
                coverUri = offlineContentCache.rewriteImportedUri(
                    entity.coverUri,
                    resourceUriMap
                )
            )
        },
        chapterContentEntities = localData.chapterContentEntities.map { entity ->
            entity.copy(
                content = offlineContentCache.rewriteImportedChapterContent(
                    entity.content,
                    resourceUriMap
                )
            )
        },
        userDataEntities = localData.userDataEntities.map { entity ->
            if (entity.type == "Uri") {
                entity.copy(
                    value = offlineContentCache.rewriteImportedUri(
                        entity.value.toUri(),
                        resourceUriMap
                    ).toString()
                )
            } else {
                entity
            }
        }
    )

    fun importLocalDataToFile(localData: LocalData): Result<Unit, Throwable> {
        val resolvedLocalData = localData.withResolvedSourceId()
        val webDataSourceId = resolvedLocalData.webBookDataSourceId
        val oldLocalDataFile = localDataDir.resolve(webDataSourceId.toString())
        if (!oldLocalDataFile.exists()) {
            return oldLocalDataFile.outputStream().buffered().use {
                runCatching {
                    it.write(Cbor.encodeToByteArray(resolvedLocalData))
                }
            }
        }
        val oldLocalData = oldLocalDataFile.inputStream().buffered().use {
            runCatching {
                localDataCbor.decodeFromByteArray<LocalData>(it.readBytes())
            }
        }.let {
            it.component1() ?: return it.asErr()
        }.withResolvedSourceId()
        val mergedLocalData = resolvedLocalData.copy(
            bookInformationEntities = mergeEntities(
                oldLocalData.bookInformationEntities,
                resolvedLocalData.bookInformationEntities,
                key = { it.sourceId to it.id }
            ),
            bookRecordEntities = mergeEntities(
                oldLocalData.bookRecordEntities,
                resolvedLocalData.bookRecordEntities,
                key = { it.bookId to it.date }
            ),
            dailyCountEntities = mergeEntities(
                oldLocalData.dailyCountEntities,
                resolvedLocalData.dailyCountEntities,
                key = { it.date }
            ),
            bookshelfEntities = mergeEntities(
                oldLocalData.bookshelfEntities,
                resolvedLocalData.bookshelfEntities,
                key = { it.id }
            ),
            bookshelfBookMetadataEntities = mergeEntities(
                oldLocalData.bookshelfBookMetadataEntities,
                resolvedLocalData.bookshelfBookMetadataEntities,
                key = { it.id }
            ),
            chapterContentEntities = mergeEntities(
                oldLocalData.chapterContentEntities,
                resolvedLocalData.chapterContentEntities,
                key = { Triple(it.sourceId, it.bookId, it.id) }
            ),
            chapterDownloadEntities = mergeByKey(
                oldLocalData.chapterDownloadEntities,
                resolvedLocalData.chapterDownloadEntities,
                key = { Triple(it.sourceId, it.bookId, it.chapterId) }
            ) { old, new -> if (new.updatedAt >= old.updatedAt) new else old },
            downloadTaskEntities = mergeByKey(
                oldLocalData.downloadTaskEntities,
                resolvedLocalData.downloadTaskEntities,
                key = { it.sourceId to it.bookId }
            ) { old, new -> if (new.updatedAt >= old.updatedAt) new else old },
            chapterInformationEntities = mergeEntities(
                oldLocalData.chapterInformationEntities,
                resolvedLocalData.chapterInformationEntities,
                key = { Triple(it.sourceId, it.bookId, it.id) }
            ),
            formattingRuleEntities = mergeEntities(
                oldLocalData.formattingRuleEntities,
                resolvedLocalData.formattingRuleEntities,
                key = { it.id }
            ),
            userDataEntities = mergeEntities(
                oldLocalData.userDataEntities,
                resolvedLocalData.userDataEntities,
                key = { it.path }
            ),
            userReadingDataEntities = mergeEntities(
                oldLocalData.userReadingDataEntities,
                resolvedLocalData.userReadingDataEntities,
                key = { it.id }
            ),
            volumeEntities = mergeEntities(
                oldLocalData.volumeEntities,
                resolvedLocalData.volumeEntities,
                key = { Triple(it.sourceId, it.bookId, it.volumeId) }
            )
        )
        return oldLocalDataFile.outputStream().buffered().use {
            runCatching {
                it.write(Cbor.encodeToByteArray(mergedLocalData))
            }
        }.also {
            runCatching {
                runBlocking { storageUsageRepository.invalidateSnapshot() }
            }
        }
    }

    private fun <K, T : Mergeable<T>> mergeEntities(
        oldEntities: List<T>,
        newEntities: List<T>,
        key: (T) -> K
    ): List<T> {
        val newByKey = newEntities.associateBy(key)
        val oldKeys = oldEntities.mapTo(mutableSetOf(), key)
        return oldEntities.map { old ->
            newByKey[key(old)]?.let { imported -> old.merge(imported) } ?: old
        } + newEntities.filter { key(it) !in oldKeys }
    }

    private fun <K, T> mergeByKey(
        oldEntities: List<T>,
        newEntities: List<T>,
        key: (T) -> K,
        merge: (T, T) -> T
    ): List<T> {
        val merged = LinkedHashMap<K, T>()
        oldEntities.forEach { merged[key(it)] = it }
        newEntities.forEach { imported ->
            val entityKey = key(imported)
            merged[entityKey] = merged[entityKey]?.let { old -> merge(old, imported) } ?: imported
        }
        return merged.values.toList()
    }

    suspend fun importLocalDataToDatabase(localData: LocalData): Result<Unit, Throwable> {
        val resolvedLocalData = localData.withResolvedSourceId()
        for (entity in resolvedLocalData.bookInformationEntities) {
            bookBookInformationDao.insert(
                bookBookInformationDao.getEntityForSource(entity.sourceId, entity.id)
                    ?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.bookRecordEntities) {
            val merged = bookRecordDao
                .getBookRecordByIdAndDate(entity.bookId, entity.date)
                ?.merge(entity)
                ?: entity
            bookRecordDao.insertBookRecord(merged)
        }
        for (entity in resolvedLocalData.dailyCountEntities) {
            val merged = dailyCountDao.getEntity(entity.date)?.merge(entity) ?: entity
            dailyCountDao.insert(merged)
        }
        for (entity in resolvedLocalData.bookshelfEntities) {
            bookshelfDao.insertBookshelf(
                bookshelfDao.getBookshelf(entity.id)?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.bookshelfBookMetadataEntities) {
            bookshelfDao.insertBookshelfBookMetadata(
                bookshelfDao.getBookshelfBookMetadataEntity(
                    entity.id
                )?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.chapterContentEntities) {
            chapterContentDao.update(
                chapterContentDao.get(entity.sourceId, entity.bookId, entity.id)?.let(entity::merge) ?: entity
            )
        }
        val existingChapterDownloadEntities = chapterDownloadDao.getAll()
            .associateBy { Triple(it.sourceId, it.bookId, it.chapterId) }
        for (entity in resolvedLocalData.chapterDownloadEntities) {
            val key = Triple(entity.sourceId, entity.bookId, entity.chapterId)
            val existing = existingChapterDownloadEntities[key]
            if (existing == null || entity.updatedAt >= existing.updatedAt) {
                chapterDownloadDao.upsert(entity)
            }
        }
        val existingDownloadTasks = downloadTaskDao.getAll().associateBy {
            it.sourceId to it.bookId
        }
        for (entity in resolvedLocalData.downloadTaskEntities) {
            val existing = existingDownloadTasks[entity.sourceId to entity.bookId]
            if (existing == null || entity.updatedAt >= existing.updatedAt) {
                downloadTaskDao.upsert(entity)
            }
        }
        for (entity in resolvedLocalData.chapterInformationEntities) {
            bookVolumesDao.insertChapterInformationEntities(
                bookVolumesDao.getChapterInformationEntity(
                    entity.sourceId,
                    entity.bookId,
                    entity.id
                )?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.volumeEntities) {
            bookVolumesDao.insertVolume(
                bookVolumesDao.getVolumeEntity(
                    entity.sourceId,
                    entity.bookId,
                    entity.volumeId
                )?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.formattingRuleEntities) {
            formattingRuleDao.update(
                formattingRuleDao.getBookRuleEntity(entity.id)?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.userReadingDataEntities) {
            userReadingDataDao.insert(
                userReadingDataDao.getEntity(entity.id)?.let(entity::merge) ?: entity
            )
        }
        for (entity in resolvedLocalData.userDataEntities) {
            userDataDao.insert(userDataDao.getEntity(entity.path)?.let(entity::merge) ?: entity)
        }
        storageUsageRepository.invalidateSnapshot()
        return Ok(Unit)
    }

    suspend fun cleanDatabaseWithoutGlobalUserData(deleteOfflineImages: Boolean = true) {
        bookBookInformationDao.clear()
        bookRecordDao.clear()
        dailyCountDao.clear()
        bookshelfDao.clear()
        bookVolumesDao.clear()
        chapterContentDao.clear()
        chapterDownloadDao.clear()
        downloadTaskDao.clear()
        if (deleteOfflineImages) offlineContentCache.deleteAllImages()
        formattingRuleDao.clear()
        userReadingDataDao.clear()

        for (entity in userDataDao.getAllEntities()) {
            if (!webDataSourceUserDataPathSet.contains(entity.path)) continue
            userDataDao.remove(entity.path)
        }
        runCatching {
            storageUsageRepository.invalidateSnapshot()
        }
    }

    init {
        registerWebDataSourceUserData(UserDataPath.Settings.Data.WebDataSourceId.path)
        registerWebDataSourceUserData(UserDataPath.ReadingBooks.path)
        registerWebDataSourceUserData(UserDataPath.CompletedDownloadBookList.path)
        registerWebDataSourceUserData(UserDataPath.Search.History.path)
    }
}
