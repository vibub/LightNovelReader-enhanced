package indi.dmzz_yyhyy.lightnovelreader.data.download

import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.dao.UserDataDao
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.userdata.UserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadProgressRepository @Inject constructor(
    userDataDao: UserDataDao,
    val bookRepository: BookRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) {
    private data class DownloadItemSnapshot(
        val progress: Float,
        val state: DownloadItemState,
        val estimatedBytes: Long,
        val writtenBytes: Long,
        val currentChapterTitle: String?,
        val waitingReason: String?,
        val errorMessage: String?
    )

    class DownItemListUserData (
        override val path: String,
        private val userDataDao: UserDataDao,
        private val bookRepository: BookRepository,
        private val sourceId: Int,
        private val sourceKey: String
    ) : UserData<List<DownloadItem>>(path) {
        override suspend fun set(value: List<DownloadItem>) {
            userDataDao.insert(path, group, "CompletedDownloadItemList", value.joinToString {
                "${it.type.name}|${it.bookId}|${it.sourceId}|${it.sourceKey}"
            })
        }

        override suspend fun get(): List<DownloadItem>? = userDataDao.get(path)?.parseItems()

        override fun getFlow(): Flow<List<DownloadItem>?> = userDataDao.getFlow(path).map {
            it?.parseItems().orEmpty()
        }

        private fun String.parseItems(): List<DownloadItem> = split(",").mapNotNull { raw ->
            val values = raw.split("|", limit = 4)
            if (values.size < 2) return@mapNotNull null
            val itemSourceId = values.getOrNull(2)?.trim()?.toIntOrNull() ?: sourceId
            val itemSourceKey = values.getOrNull(3)?.trim().orEmpty().ifBlank {
                sourceKey.takeIf { itemSourceId == sourceId }.orEmpty()
            }
            runCatching {
                MutableDownloadItem(
                    type = DownloadType.valueOf(values[0].trim()),
                    bookId = values[1].trim(),
                    bookInformationFlow = bookRepository.getBookInformationFlowForSource(
                        id = values[1].trim(),
                        sourceKey = itemSourceKey,
                        sourceId = itemSourceId
                    ),
                    sourceId = itemSourceId,
                    sourceKey = itemSourceKey
                ).apply {
                    progress = 1f
                    state = DownloadItemState.COMPLETED
                }
            }.onFailure { error ->
                Log.e("CompletedDownloadItemList", "无法解析下载记录：$raw", error)
            }.getOrNull()
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val completedBookListUserData = DownItemListUserData(
        path = UserDataPath.CompletedDownloadBookList.path,
        userDataDao = userDataDao,
        bookRepository = bookRepository,
        sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId(),
        sourceKey = webBookDataSourceProvider.value.id.toString()
    )
    private val _downloadItemList = mutableStateListOf<DownloadItem>()
    val downloadItemIdList: List<DownloadItem> get() = _downloadItemList.toList()

    init {
        coroutineScope.launch {
            val completedBookList = completedBookListUserData.getOrDefault(emptyList())
            val currentDataSource = webBookDataSourceProvider.value
            val defaultSourceId = currentDataSource.id.toLegacyCompatibleSourceId()
            val defaultSourceKey = currentDataSource.id.toString()
            val persistedTasks = downloadTaskRepository.getAll().mapNotNull { task ->
                val state = runCatching { DownloadTaskStatus.valueOf(task.state) }.getOrNull()
                    ?: return@mapNotNull null
                val taskSourceKey = task.sourceKey.ifBlank {
                    defaultSourceKey.takeIf { task.sourceId == defaultSourceId }.orEmpty()
                }
                val itemState = when (state) {
                    DownloadTaskStatus.RUNNING -> DownloadItemState.RUNNING
                    DownloadTaskStatus.PAUSED -> DownloadItemState.PAUSED
                    DownloadTaskStatus.FAILED -> DownloadItemState.FAILED
                    DownloadTaskStatus.COMPLETED -> DownloadItemState.COMPLETED
                }
                MutableDownloadItem(
                    type = DownloadType.CACHE,
                    bookId = task.bookId,
                    bookInformationFlow = bookRepository.getBookInformationFlowForSource(
                        id = task.bookId,
                        sourceKey = taskSourceKey,
                        sourceId = task.sourceId
                    ),
                    sourceId = task.sourceId,
                    sourceKey = taskSourceKey
                ).apply {
                    progress = task.progress
                    this.state = itemState
                    estimatedBytes = task.estimatedBytes
                    writtenBytes = task.writtenBytes
                    currentChapterTitle = task.currentChapterTitle
                    waitingReason = task.waitingReason
                    errorMessage = task.errorMessage
                }
            }
            _downloadItemList.addAll((completedBookList + persistedTasks).distinct())
        }
    }

    fun addExportItem(downloadItem: DownloadItem) {
        if (_downloadItemList.contains(downloadItem)) {
            _downloadItemList.removeIf { it == downloadItem }
        }
        _downloadItemList.add(downloadItem)
        coroutineScope.launch {
            snapshotFlow {
                DownloadItemSnapshot(
                    progress = downloadItem.progress,
                    state = downloadItem.state,
                    estimatedBytes = downloadItem.estimatedBytes,
                    writtenBytes = downloadItem.writtenBytes,
                    currentChapterTitle = downloadItem.currentChapterTitle,
                    waitingReason = downloadItem.waitingReason,
                    errorMessage = downloadItem.errorMessage
                )
            }.collect { snapshot ->
                val progress = snapshot.progress
                val state = snapshot.state
                when {
                    progress >= 1f && state != DownloadItemState.PAUSED -> {
                        downloadItem.progress = 1f
                        downloadItem.state = DownloadItemState.COMPLETED
                        persistTask(downloadItem)
                        completedBookListUserData.update(
                            updater = { downloadItems ->
                                downloadItems.filterNot { it == downloadItem } + downloadItem
                            },
                            default = emptyList()
                        )
                        return@collect
                    }
                    progress < 0f && state != DownloadItemState.PAUSED -> {
                        downloadItem.state = DownloadItemState.FAILED
                        persistTask(downloadItem)
                    }
                    else -> persistTask(downloadItem)
                }
            }
        }
    }

    suspend fun persistState(downloadItem: DownloadItem, state: DownloadItemState) {
        downloadItem.state = state
        persistTask(downloadItem)
    }

    fun removeExportItem(downloadItem: DownloadItem) {
        _downloadItemList.remove(downloadItem)
        if (downloadItem.type == DownloadType.CACHE) {
            coroutineScope.launch {
                downloadTaskRepository.clear(downloadItem.sourceId, downloadItem.bookId)
            }
        }
    }

    private suspend fun persistTask(downloadItem: DownloadItem) {
        if (downloadItem.type != DownloadType.CACHE) return
        val progress = downloadItem.progress.coerceIn(0f, 1f)
        downloadTaskRepository.updateItemState(
            sourceId = downloadItem.sourceId,
            bookId = downloadItem.bookId,
            state = when (downloadItem.state) {
                DownloadItemState.RUNNING -> DownloadTaskStatus.RUNNING
                DownloadItemState.PAUSED -> DownloadTaskStatus.PAUSED
                DownloadItemState.FAILED -> DownloadTaskStatus.FAILED
                DownloadItemState.COMPLETED -> DownloadTaskStatus.COMPLETED
            },
            progress = progress,
            sourceKey = downloadItem.sourceKey,
            estimatedBytes = downloadItem.estimatedBytes,
            writtenBytes = downloadItem.writtenBytes,
            currentChapterTitle = downloadItem.currentChapterTitle,
            waitingReason = downloadItem.waitingReason,
            errorMessage = downloadItem.errorMessage
        )
    }
}