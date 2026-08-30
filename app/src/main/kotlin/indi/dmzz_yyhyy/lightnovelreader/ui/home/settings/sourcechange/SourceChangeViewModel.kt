package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sourcechange

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.work.WorkManager
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadTaskRepository
import indi.dmzz_yyhyy.lightnovelreader.data.download.DownloadTaskStatus
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.andThen
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.github.michaelbull.result.runCatching
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalData
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.ofId
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import indi.dmzz_yyhyy.lightnovelreader.utils.readAppLocalData
import indi.dmzz_yyhyy.lightnovelreader.utils.restart
import indi.dmzz_yyhyy.lightnovelreader.utils.writeAppLocalData
import io.nightfish.lightnovelreader.api.identifier.Identifier
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import javax.inject.Inject

@HiltViewModel
class SourceChangeViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localDataManager: LocalDataManager,
    private val userDataRepository: UserDataRepository,
    webBookDataSourceManager: WebBookDataSourceManager,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableSourceChangeUiState().apply {
        currentSourceId = webBookDataSourceProvider.value.id
        webDataSourceItems = webBookDataSourceManager.webDataSourceItems
    }
    val uiState: SourceChangeUiState = _uiState
    private suspend fun stopCacheWorksBeforeSourceChange() {
        val tasks = downloadTaskRepository.getAll().filter { task ->
            task.state == DownloadTaskStatus.RUNNING.name ||
                task.state == DownloadTaskStatus.PAUSED.name
        }
        tasks.forEach { task ->
            workManager.cancelUniqueWork(CacheBookWork.ofId(task.sourceId, task.bookId))
            // 兼容升级前使用全局 cache:$bookId 命名的任务。
            workManager.cancelUniqueWork(CacheBookWork.ofId(task.bookId))
        }
        withTimeoutOrNull(15.seconds) {
            tasks.forEach { task ->
                workManager.getWorkInfosForUniqueWorkFlow(
                    CacheBookWork.ofId(task.sourceId, task.bookId)
                ).filter { infos -> infos.none { !it.state.isFinished } }.first()
            }
        }
        tasks.forEach { task ->
            val current = downloadTaskRepository.get(task.sourceId, task.bookId)
            if (current?.state == DownloadTaskStatus.RUNNING.name) {
                downloadTaskRepository.markPaused(
                    sourceId = current.sourceId,
                    bookId = current.bookId,
                    progress = current.progress,
                    total = current.total,
                    processed = current.processed,
                    sourceKey = current.sourceKey,
                    waitingReason = "切换数据源前已暂停",
                    estimatedBytes = current.estimatedBytes,
                    writtenBytes = current.writtenBytes,
                    currentChapterId = current.currentChapterId,
                    currentChapterTitle = current.currentChapterTitle
                )
            }
        }
    }

    @Suppress("OPT_IN_USAGE")
    fun changeWebSource(newWebDataSourceId: Identifier) {
        if (newWebDataSourceId == _uiState.currentSourceId) return
        if (_uiState.isProcessing) return

        _uiState.isProcessing = true

        viewModelScope.launch(Dispatchers.IO) {
            var isCleanedLocalData = false
            stopCacheWorksBeforeSourceChange()
            localDataManager.exportCurrentLocalData(
                sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            )
                .andThen { data ->
                    runCatching {
                        val webBookDataSourceId = webBookDataSourceProvider.value.id
                        localDataManager.localDataDir
                            .resolve(webBookDataSourceId.toString())
                            .also {
                                if (it.exists()) it.delete()
                            }
                            .outputStream()
                            .use {
                                it.writeAppLocalData(Cbor.encodeToByteArray(data))
                            }
                    }
                }.andThen {
                    isCleanedLocalData = true
                    runCatching {
                        localDataManager.cleanDatabaseWithoutGlobalUserData(deleteOfflineImages = false)
                    }
                }.andThen out@ {
                    val file = if (newWebDataSourceId == "Wenku8".ofId()) localDataManager.localDataDir.resolve("-791439186")
                    else localDataManager.localDataDir.resolve(newWebDataSourceId.toString())
                    if (!file.exists()) return@out Ok(Unit)
                    runCatching {
                        file
                            .inputStream()
                            .use {
                                Cbor.decodeFromByteArray<LocalData>(it.readAppLocalData())
                            }
                    }.andThen {
                        localDataManager.importLocalDataToDatabase(it)
                    }
                }.andThen {
                    runCatching {
                        userDataRepository.stringUserData(UserDataPath.Settings.Data.WebDataSourceId.path).set(newWebDataSourceId.toString())
                    }
                }.onErr {
                    viewModelScope.launch(Dispatchers.Main) {
                        Toast.makeText(appContext, "Failed to change data source. Please check the log for more information", Toast.LENGTH_LONG).show()
                    }
                    Log.e("SourceChangeViewModel", "Failed to change data source.")
                    it.printStackTrace()
                    if (isCleanedLocalData) rollbackData()
                    _uiState.isProcessing = false
                    return@launch
                }.onOk {
                    restart(appContext)
                }
            _uiState.currentSourceId = newWebDataSourceId
        }
    }

    @Suppress("OPT_IN_USAGE")
    fun rollbackData() {
        viewModelScope.launch(Dispatchers.IO) {
            val webBookDataSourceId = webBookDataSourceProvider.value.id
            val localData =
                localDataManager.localDataDir
                    .resolve(webBookDataSourceId.toString())
                    .inputStream()
                    .use {
                        Cbor.decodeFromByteArray<LocalData>(it.readAppLocalData())
                    }
            localDataManager.importLocalDataToDatabase(localData)
        }
    }
}