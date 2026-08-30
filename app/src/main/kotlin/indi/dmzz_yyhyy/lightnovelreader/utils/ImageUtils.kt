package indi.dmzz_yyhyy.lightnovelreader.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale

data class EncodedImageFormat(
    val extension: String,
    val mediaType: String
)

object ImageUtils {
    private val encodedImageHttpClient = OkHttpClient()

    suspend fun uriToBitmap(
        imageUri: Uri,
        context: Context,
        header: Map<String, String> = emptyMap()
    ):  Result<Bitmap, Throwable> = withContext(Dispatchers.IO) {
        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(imageUri)
                .interceptorCoroutineContext(Dispatchers.IO)
                .httpHeaders(
                    NetworkHeaders.Builder().apply {
                        header.forEach { (key, value) -> add(key, value) }
                    }.build()
                )
                .build()

            val result = loader.execute(request)

            if (result is SuccessResult) {
                val bitmap = (result.image as? BitmapImage)?.bitmap
                if (bitmap != null) {
                    return@withContext Ok(bitmap)
                } else {
                    return@withContext Err(Throwable("Failed to cast image to BitmapImage"))
                }
            } else if (result is ErrorResult) {
                return@withContext Err(result.throwable)
            } else {
                return@withContext Err(Throwable("Unknown result type"))
            }
        } catch (_: Throwable) {
            withContext(Dispatchers.Main) {
                return@withContext Err(Throwable("Failed to cast drawable to BitmapDrawable"))
            }
        }
    }

    /**
     * 将图片的原始编码复制到目标文件，不经过 Bitmap 解码和重新压缩。
     * 返回值中的扩展名和 MIME 类型由文件头优先判定，避免 URL 后缀不可信。
     */
    suspend fun copyEncodedImage(
        imageUri: Uri,
        context: Context,
        targetFile: File,
        header: Map<String, String> = emptyMap()
    ): Result<EncodedImageFormat, Throwable> = withContext(Dispatchers.IO) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            when (imageUri.scheme?.lowercase(Locale.ROOT)) {
                "http", "https" -> {
                    val request = Request.Builder()
                        .url(imageUri.toString())
                        .headers(Headers.Builder().apply {
                            header.forEach { (key, value) -> add(key, value) }
                        }.build())
                        .get()
                        .build()
                    encodedImageHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw IOException("图片请求失败：HTTP ${response.code}")
                        }
                        val body = response.body
                        body.byteStream().use { input ->
                            copyAndDetect(
                                input = input,
                                targetFile = targetFile,
                                fallbackMimeType = body.contentType()?.toString(),
                                fallbackName = imageUri.lastPathSegment
                            )
                        }
                    }
                }
                "file" -> {
                    val file = imageUri.path?.let(::File)
                        ?: throw IOException("图片文件路径为空")
                    if (!file.isFile) throw IOException("图片文件不存在：$file")
                    file.inputStream().use { input ->
                        copyAndDetect(
                            input = input,
                            targetFile = targetFile,
                            fallbackName = file.name
                        )
                    }
                }
                "content", "android.resource" -> {
                    val input = context.contentResolver.openInputStream(imageUri)
                        ?: throw IOException("无法打开图片 URI：$imageUri")
                    input.use {
                        copyAndDetect(
                            input = it,
                            targetFile = targetFile,
                            fallbackMimeType = context.contentResolver.getType(imageUri),
                            fallbackName = imageUri.lastPathSegment
                        )
                    }
                }
                else -> throw IOException("不支持的图片 URI：$imageUri")
            }
        }.fold(
            onSuccess = { Ok(it) },
            onFailure = {
                targetFile.delete()
                if (it is CancellationException) throw it
                Err(it)
            }
        )
    }

    private fun copyAndDetect(
        input: java.io.InputStream,
        targetFile: File,
        fallbackMimeType: String? = null,
        fallbackName: String? = null
    ): EncodedImageFormat {
        val prefix = ByteArray(16)
        var read = 0
        while (read < prefix.size) {
            val count = input.read(prefix, read, prefix.size - read)
            if (count <= 0) break
            read += count
        }
        if (read <= 0) throw IOException("图片内容为空")
        val format = detectEncodedImageFormat(prefix, read)
            ?: formatFromMimeType(fallbackMimeType)
            ?: formatFromName(fallbackName)
            ?: throw IOException("无法识别图片格式")
        targetFile.outputStream().use { output ->
            output.write(prefix, 0, read)
            input.copyTo(output)
        }
        if (targetFile.length() <= 0L) throw IOException("图片写入结果为空")
        return format
    }

    private fun detectEncodedImageFormat(bytes: ByteArray, size: Int): EncodedImageFormat? {
        fun startsWith(vararg expected: Int): Boolean = size >= expected.size &&
            expected.indices.all { bytes[it].toInt() and 0xff == expected[it] }
        return when {
            startsWith(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) ->
                EncodedImageFormat("png", "image/png")
            size >= 6 && bytes.copyOf(6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") ->
                EncodedImageFormat("gif", "image/gif")
            startsWith(0xff, 0xd8, 0xff) -> EncodedImageFormat("jpg", "image/jpeg")
            size >= 12 && bytes.copyOf(4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" ->
                EncodedImageFormat("webp", "image/webp")
            startsWith(0x42, 0x4d) -> EncodedImageFormat("bmp", "image/bmp")
            else -> null
        }
    }

    private fun formatFromMimeType(mimeType: String?): EncodedImageFormat? = when (
        mimeType?.substringBefore(';')?.lowercase(Locale.ROOT)
    ) {
        "image/png" -> EncodedImageFormat("png", "image/png")
        "image/gif" -> EncodedImageFormat("gif", "image/gif")
        "image/jpeg", "image/jpg" -> EncodedImageFormat("jpg", "image/jpeg")
        "image/webp" -> EncodedImageFormat("webp", "image/webp")
        "image/bmp" -> EncodedImageFormat("bmp", "image/bmp")
        else -> null
    }

    private fun formatFromName(name: String?): EncodedImageFormat? = when (
        name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
    ) {
        "png" -> EncodedImageFormat("png", "image/png")
        "gif" -> EncodedImageFormat("gif", "image/gif")
        "jpg", "jpeg" -> EncodedImageFormat("jpg", "image/jpeg")
        "webp" -> EncodedImageFormat("webp", "image/webp")
        "bmp" -> EncodedImageFormat("bmp", "image/bmp")
        else -> null
    }

    suspend fun saveBitmapAsPng(
        context: Context,
        bitmap: Bitmap
    ): Result<String, Throwable> =
        withContext(Dispatchers.IO) {
            try {
                val fileName = "${System.currentTimeMillis()}.png"
                val mimeType = "image/png"

                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/LightNovelReader"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Err(Throwable("failed to get uri"))

                resolver.openOutputStream(uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Ok(fileName)
            } catch (e: Exception) {
                e.printStackTrace()
                Err(e)
            }
        }
}