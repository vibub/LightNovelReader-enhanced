package indi.dmzz_yyhyy.lightnovelreader.data.download

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity.DownloadTaskEntity
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.work.CacheBookWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 负责让已经运行的缓存任务跟随下载设置变化重新应用 WorkManager 约束。
 *
 * 任务的章节队列和 queueAll 标记保存在数据库中，重新排队时会复用原任务的选择语义，
 * 因此不会把按章节或按卷下载恢复成整本下载。
 */
@Singleton
class DownloadWorkScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val downloadSettingsRepository: DownloadSettingsRepository,
    private val downloadTaskRepository: DownloadTaskRepository,
    private val webBookDataSourceManager: WebBookDataSourceManager
) {
    companion object {
        private const val TAG = "DownloadWorkScheduler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rescheduleMutex = Mutex()

    @Volatile
    private var started = false

    /** 在应用进程中启动一次设置监听。 */
    fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            scope.launch {
                downloadSettingsRepository.getFlow().collect { settings ->
                    runCatching {
                        rescheduleRunningTasks(settings)
                    }.onFailure { throwable ->
                        Log.e(TAG, "无法根据下载设置重新排队缓存任务", throwable)
                    }
                }
            }
        }
    }

    internal suspend fun rescheduleRunningTasks(settings: DownloadSettings) {
        rescheduleMutex.withLock {
            downloadTaskRepository.getAll()
                .filter { it.state == DownloadTaskStatus.RUNNING.name }
                .forEach { task ->
                    rescheduleTask(task, settings)
                }
        }
    }

    private suspend fun rescheduleTask(
        task: DownloadTaskEntity,
        settings: DownloadSettings
    ) {
        // 用户可能在遍历任务期间暂停或取消了当前任务，避免把它重新唤醒。
        if (downloadTaskRepository.get(task.sourceId, task.bookId)?.state !=
            DownloadTaskStatus.RUNNING.name
        ) {
            return
        }

        val uniqueName = CacheBookWork.ofId(task.sourceId, task.bookId)
        val activeWork = findActiveWork(uniqueName)
        if (activeWork != null && task.constraintsKey == settings.constraintsKey) {
            return
        }
        val legacyUniqueName = CacheBookWork.ofId(task.bookId)
        val activeLegacyWork = if (activeWork == null && task.sourceKey.isBlank()) {
            findActiveWork(legacyUniqueName)
        } else {
            null
        }
        val sourceKey = task.sourceKey.takeIf(String::isNotBlank)
            ?: webBookDataSourceManager
                .getWebDataSourceProvider(task.sourceId)
                ?.id
                ?.toString()
                .orEmpty()
        // 旧版本没有 queue_all 和 constraints_key；只对这类尚未开始的旧任务保留整本语义。
        val queueAll = task.queueAll || (
            task.constraintsKey.isBlank() && task.total == 0 && task.processed == 0
        )

        val request = OneTimeWorkRequestBuilder<CacheBookWork>()
            .setConstraints(settings.constraints())
            .setInputData(
                workDataOf(
                    "bookId" to task.bookId,
                    "sourceKey" to sourceKey,
                    "sourceId" to task.sourceId,
                    "queueAll" to queueAll
                )
            )
            .build()

        if (activeLegacyWork != null) {
            // 旧版任务使用全局 cache:$bookId 命名，必须先取消，避免两个 Worker 同时写缓存。
            workManager.cancelUniqueWork(legacyUniqueName)
        }
        if (downloadTaskRepository.get(task.sourceId, task.bookId)?.state !=
            DownloadTaskStatus.RUNNING.name
        ) {
            return
        }
        // 先写入本次约束，再排队 Worker，避免 Worker 快速完成后被旧状态覆盖回 RUNNING。
        downloadTaskRepository.markRunning(
            sourceId = task.sourceId,
            bookId = task.bookId,
            sourceKey = sourceKey,
            queueAll = queueAll,
            constraintsKey = settings.constraintsKey
        )
        workManager.enqueueUniqueWork(
            uniqueName,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private suspend fun findActiveWork(uniqueName: String): WorkInfo? =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName)
            .first()
            .firstOrNull { !it.state.isFinished }
}
