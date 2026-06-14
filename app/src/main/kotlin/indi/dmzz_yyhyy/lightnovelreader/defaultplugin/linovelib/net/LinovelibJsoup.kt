package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.util.Log
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.utils.network.UserAgentGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException

class LinovelibBlockedException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class LinovelibHttpException(
    val statusCode: Int,
    val targetUrl: String,
    cause: Throwable? = null
) : IOException("HTTP $statusCode: $targetUrl", cause)

class LinovelibJsoup(
    private val accountStore: LinovelibAccountStore? = null
) {
    private val semaphore = Semaphore(3)

    suspend fun getDocument(
        url: String,
        referer: String = LinovelibConstants.BASE_URL,
        useCookie: Boolean = true,
        retryTime: Int = 2
    ): Document = fetch(
        url = url,
        referer = referer,
        accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        useCookie = useCookie,
        retryTime = retryTime
    ).let { body ->
        Jsoup.parse(body, url).apply {
            outputSettings().prettyPrint(false)
        }
    }

    suspend fun getRaw(
        url: String,
        referer: String = LinovelibConstants.BASE_URL,
        accept: String = "application/json,text/plain,*/*",
        useCookie: Boolean = true,
        retryTime: Int = 2
    ): String = fetch(url, referer, accept, useCookie, retryTime)

    fun defaultHeaders(
        referer: String = LinovelibConstants.BASE_URL,
        useCookie: Boolean = true
    ): Map<String, String> = buildMap {
        put("User-Agent", UserAgentGenerator.generate())
        put("Accept", "image/webp,image/jpeg,image/png,image/gif,image/*,*/*;q=0.8")
        put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        put("Referer", referer)
        put("Cache-Control", "no-cache")
        if (useCookie) {
            accountStore?.getCookie()?.takeIf { it.isNotBlank() }?.let {
                put("Cookie", it)
            }
        }
    }

    private suspend fun fetch(
        url: String,
        referer: String,
        accept: String,
        useCookie: Boolean,
        retryTime: Int
    ): String = withContext(Dispatchers.IO) {
        semaphore.withPermit {
            var lastError: Throwable? = null
            var delayMillis = 1_500L
            repeat(retryTime + 1) { attempt ->
                try {
                    val response = Jsoup.connect(url)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(false)
                        .followRedirects(true)
                        .timeout(15_000)
                        .headers(defaultHeaders(referer, useCookie) + ("Accept" to accept))
                        .execute()
                    val body = response.bodyAsBytes().toString(Charsets.UTF_8)
                    if (isCloudflareBlocked(response.statusCode(), body)) {
                        throw LinovelibBlockedException("Linovelib request was blocked by Cloudflare: $url")
                    }
                    return@withPermit body
                } catch (e: HttpStatusException) {
                    lastError = if (e.statusCode == 403 || e.statusCode == 503) {
                        LinovelibBlockedException("Linovelib request was blocked: ${e.statusCode} $url", e)
                    } else {
                        LinovelibHttpException(e.statusCode, url, e)
                    }
                    if (!shouldRetry(lastError)) throw lastError
                } catch (e: SocketTimeoutException) {
                    lastError = e
                } catch (e: IOException) {
                    lastError = e
                    if (!shouldRetry(e)) throw e
                } catch (e: Throwable) {
                    lastError = e
                    throw e
                }
                if (attempt < retryTime) {
                    Log.w(TAG, "request failed, retrying: $url", lastError)
                    delay(delayMillis)
                    delayMillis *= 2
                }
            }
            throw lastError ?: IOException("Linovelib request failed: $url")
        }
    }

    private fun shouldRetry(error: Throwable?): Boolean = when (error) {
        is LinovelibBlockedException -> false
        is LinovelibHttpException -> error.statusCode >= 500
        else -> true
    }

    companion object {
        private const val TAG = "LinovelibJsoup"

        fun isCloudflareBlocked(statusCode: Int, body: String): Boolean {
            if (statusCode == 403 || statusCode == 503) return true
            val text = body.lowercase()
            return ("cloudflare" in text && (
                "cf-chl" in text ||
                    "checking your browser" in text ||
                    "just a moment" in text ||
                    "attention required" in text
                )) || "请稍候" in body && "正在检查您的浏览器" in body
        }

        fun normalizeUrl(url: String): String {
            val trimmedUrl = url.trim().replace("&amp;", "&")
            return when {
                trimmedUrl.isBlank() -> ""
                trimmedUrl.startsWith("data:", ignoreCase = true) -> ""
                trimmedUrl.startsWith("javascript:", ignoreCase = true) -> ""
                trimmedUrl.startsWith("about:", ignoreCase = true) -> ""
                trimmedUrl.startsWith("blob:", ignoreCase = true) -> ""
                trimmedUrl.startsWith("//") -> "https:$trimmedUrl"
                trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://") -> trimmedUrl
                trimmedUrl.startsWith("/") -> LinovelibConstants.BASE_URL + trimmedUrl
                else -> "${LinovelibConstants.BASE_URL}/$trimmedUrl"
            }
        }
    }
}
