package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.util.Log
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.utils.network.UserAgentGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class LinovelibBlockedException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class LinovelibHttpException(
    val statusCode: Int,
    targetUrl: String,
    val retryAfterMillis: Long? = null,
    cause: Throwable? = null
) : IOException("HTTP $statusCode: $targetUrl", cause)

class LinovelibJsoup(
    private val accountStore: LinovelibAccountStore? = null
) {
    enum class UserAgentMode {
        Desktop,
        Mobile
    }

    private val desktopUserAgent = UserAgentGenerator.generateDesktop()
    private val mobileUserAgent = UserAgentGenerator.generate()

    suspend fun getDocument(
        url: String,
        referer: String = LinovelibConstants.BASE_URL,
        useCookie: Boolean = true,
        retryTime: Int = 2,
        userAgentMode: UserAgentMode = UserAgentMode.Desktop
    ): Document = fetch(
        url = url,
        referer = referer,
        accept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        useCookie = useCookie,
        retryTime = retryTime,
        userAgentMode = userAgentMode
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
        retryTime: Int = 2,
        userAgentMode: UserAgentMode = UserAgentMode.Desktop
    ): String = fetch(url, referer, accept, useCookie, retryTime, userAgentMode)

    fun defaultHeaders(
        referer: String = LinovelibConstants.BASE_URL,
        useCookie: Boolean = true,
        userAgentMode: UserAgentMode = UserAgentMode.Desktop
    ): Map<String, String> = buildMap {
        put("User-Agent", userAgentMode.userAgent())
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

    private fun UserAgentMode.userAgent(): String = when (this) {
        UserAgentMode.Desktop -> desktopUserAgent
        UserAgentMode.Mobile -> mobileUserAgent
    }

    private suspend fun fetch(
        url: String,
        referer: String,
        accept: String,
        useCookie: Boolean,
        retryTime: Int,
        userAgentMode: UserAgentMode
    ): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        var delayMillis = 1_500L
        repeat(retryTime + 1) { attempt ->
            try {
                val body = LinovelibRateLimiter.run {
                    val response = Jsoup.connect(url)
                        .ignoreContentType(true)
                        .ignoreHttpErrors(true)
                        .followRedirects(true)
                        .timeout(15_000)
                        .headers(defaultHeaders(referer, useCookie, userAgentMode) + ("Accept" to accept))
                        .execute()
                    val body = response.bodyAsBytes().toString(Charsets.UTF_8)
                    val statusCode = response.statusCode()
                    if (statusCode == 429) {
                        val retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After"))
                        LinovelibRateLimiter.coolDown(
                            reason = "HTTP 429 $url",
                            delayMillis = retryAfterMillis ?: LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS
                        )
                        throw LinovelibHttpException(statusCode, url, retryAfterMillis)
                    }
                    if (isCloudflareBlocked(statusCode, body)) {
                        LinovelibRateLimiter.coolDown(
                            reason = "Cloudflare blocked $url",
                            delayMillis = LinovelibRateLimiter.CLOUDFLARE_COOLDOWN_MILLIS
                        )
                        throw LinovelibBlockedException("Linovelib request was blocked by Cloudflare: $url")
                    }
                    if (statusCode !in 200..299) throw LinovelibHttpException(statusCode, url)
                    body
                }
                return@withContext body
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
                if (!shouldRetry(e)) throw e
            } catch (e: Throwable) {
                throw e
            }
            if (attempt < retryTime) {
                val retryDelay = retryDelayMillis(lastError, delayMillis)
                Log.w(TAG, "request failed, retrying in ${retryDelay}ms: $url", lastError)
                delay(retryDelay.milliseconds)
                delayMillis = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
        throw lastError ?: IOException("Linovelib request failed: $url")
    }

    private fun shouldRetry(error: Throwable?): Boolean = when (error) {
        is LinovelibBlockedException -> false
        is LinovelibHttpException -> error.statusCode == 429 || error.statusCode >= 500
        else -> true
    }

    private fun retryDelayMillis(error: Throwable?, currentDelayMillis: Long): Long {
        val baseDelay = when (error) {
            is LinovelibHttpException -> if (error.statusCode == 429) {
                maxOf(currentDelayMillis, error.retryAfterMillis ?: RATE_LIMIT_RETRY_DELAY_MILLIS)
            } else {
                currentDelayMillis
            }
            else -> currentDelayMillis
        }
        val cappedBaseDelay = baseDelay.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        val jitterBound = (MAX_RETRY_DELAY_MILLIS - cappedBaseDelay).coerceIn(0L, RETRY_JITTER_MILLIS)
        return cappedBaseDelay + if (jitterBound > 0L) Random.nextLong(jitterBound + 1) else 0L
    }

    companion object {
        private const val TAG = "LinovelibJsoup"
        private const val RATE_LIMIT_RETRY_DELAY_MILLIS = 5_000L
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private const val RETRY_JITTER_MILLIS = 1_000L

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

        fun parseRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
            val text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            text.toLongOrNull()?.let { seconds ->
                if (seconds <= 0L) return 0L
                return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
            }
            return runCatching {
                ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
                    .minus(nowMillis)
                    .coerceAtLeast(0L)
            }.getOrNull()
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
