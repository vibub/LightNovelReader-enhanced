package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.michaelbull.result.onErr
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.AppLocalData
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalDataArchive
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.localDataCbor
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import java.util.UUID
import kotlinx.coroutines.CancellationException

@HiltWorker
class ImportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ImportDataWork"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val overwrite = inputData.getBoolean("overwrite", false)
        val stagingDirectory = applicationContext.cacheDir
            .resolve("lnr-import-${UUID.randomUUID()}")
        stagingDirectory.mkdirs()
        try {
            val archive = applicationContext.contentResolver.openInputStream(fileUri)?.use {
                LocalDataArchive.read(it, stagingDirectory)
            } ?: return Result.failure()
            val appLocalData = localDataCbor.decodeFromByteArray<AppLocalData>(archive.data)
            val resourceUriMap = archive.manifest?.resources
                ?.associate { resource ->
                    resource.sourceUri to Uri.fromFile(
                        applicationContext.filesDir.resolve(resource.targetPath)
                    ).toString()
                }
                .orEmpty()
            val importResult = localDataManager.importAppLocalData(
                appLocalData = appLocalData,
                stagingDirectory = archive.stagingDirectory,
                resourceUriMap = resourceUriMap,
                overwrite = overwrite
            )
            var failed = false
            importResult.onErr {
                failed = true
                Log.e(TAG, "Failed to import the data")
                it.printStackTrace()
            }
            return if (failed) Result.failure() else Result.success()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Log.e(TAG, "Failed to load or import file", throwable)
            return Result.failure()
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }
}
