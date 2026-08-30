package indi.dmzz_yyhyy.lightnovelreader.utils.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ListenableWorker
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import indi.dmzz_yyhyy.lightnovelreader.utils.EncodedImageFormat
import indi.dmzz_yyhyy.lightnovelreader.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds

class ImageDownloader(
    private val context: Context,
    private val tasks: List<Task>,
    val onProgress: (Int, Int) -> Unit,
) {
    var count = 0
        private set

    data class Task(
        val file: File,
        val uri: Uri,
        val header: Map<String, String> = emptyMap(),
        val onCompleted: (File, EncodedImageFormat) -> Unit = { _, _ -> }
    )

    suspend fun run(): ListenableWorker.Result = withContext(Dispatchers.IO) {
        Log.i("ImageDownloader", "total tasks: ${tasks.size}")
        tasks.forEach { task ->
            val result = downloadWithRetry(task, maxRetry = 3)
            result
                .onOk { format ->
                    try {
                        task.file.parentFile?.mkdirs()
                        val actualFile = moveToDetectedExtension(task.file, format.extension)
                        task.onCompleted(actualFile, format)
                    } catch (e: Exception) {
                        Log.e(
                            "ImageDownloader",
                            "task $count: file write failed, file=${task.file}",
                            e
                        )
                        return@withContext ListenableWorker.Result.failure()
                    }
                }
                .onErr { t ->
                    Log.e(
                        "ImageDownloader",
                        "task $count failed, uri=${task.uri}",
                        t
                    )
                    return@withContext ListenableWorker.Result.failure()
                }
            count++
            onProgress(count, tasks.size)
            Log.i("ImageDownloader", "tasks: $count/${tasks.size}")
        }
        return@withContext ListenableWorker.Result.success()
    }

    private fun moveToDetectedExtension(file: File, extension: String): File {
        val normalizedExtension = extension.lowercase()
        if (file.extension.equals(normalizedExtension, ignoreCase = true)) return file
        val target = file.resolveSibling("${file.nameWithoutExtension}.$normalizedExtension")
        if (target != file && target.exists()) target.delete()
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = true)
            file.delete()
        }
        return target
    }

    private suspend fun downloadWithRetry(
        task: Task,
        maxRetry: Int
    ): Result<EncodedImageFormat, Throwable> {
        var lastError: Throwable? = null

        repeat(maxRetry) { attempt ->
            val result = ImageUtils.copyEncodedImage(
                imageUri = task.uri,
                context = context,
                targetFile = task.file,
                header = task.header
            )
            var shouldRetry = false

            result
                .onOk {
                    return result
                }
                .onErr { error ->
                    lastError = error
                    if (error is SocketTimeoutException ||
                        error is ConnectException ||
                        error is IOException
                    ) {
                        shouldRetry = true
                        Log.w(
                            "ImageDownloader",
                            "retry ${attempt + 1}/$maxRetry for ${task.uri} (cause: ${error.cause})"
                        )
                    } else {
                        return result
                    }
                }
            if (shouldRetry) {
                delay((500L * (attempt + 1)).milliseconds)
            }
        }
        return Err(lastError ?: RuntimeException("unknown error"))
    }
}
