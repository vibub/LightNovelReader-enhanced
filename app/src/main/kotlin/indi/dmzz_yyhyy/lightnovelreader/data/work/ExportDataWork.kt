package indi.dmzz_yyhyy.lightnovelreader.data.work

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.local.cbor.LocalDataArchive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray
import java.io.FileOutputStream

@HiltWorker
class ExportDataWork @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val localDataManager: LocalDataManager
) : CoroutineWorker(appContext, workerParams) {
    companion object {
        const val TAG = "ExportDataWork"
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        val fileUri = inputData.getString("uri")?.let(Uri::parse) ?: return Result.failure()
        val exportLocalBookCache = inputData.getBoolean("exportLocalBookCache", true)
        val exportBookshelf = inputData.getBoolean("exportBookshelf", true)
        val exportReadingData = inputData.getBoolean("exportReadingData", true)
        val exportSetting = inputData.getBoolean("exportSetting", true)
        val appLocalData = localDataManager.exportAppLocalData(
            localBookCache = exportLocalBookCache,
            bookshelf = exportBookshelf,
            readingRecord = exportReadingData,
            settings = exportSetting
        ).component1() ?: run {
            Log.e(TAG, "Failed to get AppLocalData")
            return Result.failure()
        }

        val resources = try {
            localDataManager.exportableResources(appLocalData)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to collect backup resources", throwable)
            return Result.failure()
        }
        return try {
            val written = applicationContext.contentResolver.openFileDescriptor(fileUri, "w")
                ?.use { parcelFileDescriptor ->
                    FileOutputStream(parcelFileDescriptor.fileDescriptor).use {
                        LocalDataArchive.write(
                            output = it,
                            data = Cbor.encodeToByteArray(appLocalData),
                            resources = resources
                        )
                    }
                    true
                } ?: false
            if (written) Result.success() else Result.failure()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to write backup", throwable)
            Result.failure()
        } finally {
            localDataManager.cleanupTemporaryExportResources(resources)
        }
    }
}
