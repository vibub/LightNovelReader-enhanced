package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.util.Log
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.utils.network.UserAgentGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
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
    ): String = fetch(
        url = url,
        referer = referer,
        accept = accept,
        useCookie = useCookie,
        retryTime = retryTime,
        userAgentMode = userAgentMode
    )

    suspend fun postFormRaw(
        url: String,
        formData: Map<String, String>,
        referer: String = LinovelibConstants.BASE_URL,
        accept: String = "application/json,text/javascript,*/*;q=0.01",
        useCookie: Boolean = true,
        retryTime: Int = 2,
        userAgentMode: UserAgentMode = UserAgentMode.Desktop
    ): String = fetch(
        url = url,
        referer = referer,
        accept = accept,
        useCookie = useCookie,
        retryTime = retryTime,
        userAgentMode = userAgentMode,
        method = Connection.Method.POST,
        formData = formData,
        extraHeaders = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest"
        )
    )

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
            accountStore?.getCookie()
                ?.let(::sanitizeLinovelibRequestCookie)
                ?.takeIf { it.isNotBlank() }
                ?.let { put("Cookie", it) }
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
        userAgentMode: UserAgentMode,
        method: Connection.Method = Connection.Method.GET,
        formData: Map<String, String> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap()
    ): String = withContext(Dispatchers.IO) {
        if (useCookie) accountStore?.refreshCookie()
        var lastError: Throwable? = null
        var delayMillis = 1_500L
        repeat(retryTime + 1) { attempt ->
            try {
                val session = Jsoup.newSession()
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .followRedirects(userAgentMode == UserAgentMode.Mobile)
                    .timeout(15_000)
                    .headers(
                        defaultHeaders(referer, useCookie, userAgentMode) +
                            ("Accept" to accept) + extraHeaders
                    )
                var requestUrl = url
                var redirectCount = 0
                while (true) {
                    if (userAgentMode == UserAgentMode.Desktop && !LinovelibConstants.isDesktopPageUrl(requestUrl)) {
                        throw LinovelibBlockedException("Linovelib 后台请求只允许电脑端地址，请使用网页浏览打开其他站点")
                    }
                    var redirectUrl: String? = null
                    val body = LinovelibRateLimiter.run {
                        val connection = session.newRequest().url(requestUrl).method(method)
                        if (formData.isNotEmpty()) connection.data(formData)
                        val response = connection.execute()
                        val body = response.bodyAsBytes().toString(Charsets.UTF_8)
                        val statusCode = response.statusCode()
                        if (userAgentMode == UserAgentMode.Desktop && statusCode in REDIRECT_STATUS_CODES) {
                            // 每一跳分别限频；不跟随移动域名，也不自动重放重定向后的 POST。
                            if (method != Connection.Method.GET || redirectCount >= MAX_REDIRECTS) {
                                throw LinovelibBlockedException("Linovelib 后台请求重定向异常，请使用网页浏览处理")
                            }
                            redirectUrl = resolveDesktopRedirect(requestUrl, response.header("Location").orEmpty())
                            return@run ""
                        }
                        val retryAfterMillis = parseRetryAfterMillis(response.header("Retry-After"))
                        if (statusCode == 429) {
                            LinovelibRateLimiter.coolDown(
                                reason = "HTTP 429 $requestUrl",
                                delayMillis = retryAfterMillis ?: LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS
                            )
                            throw LinovelibHttpException(statusCode, requestUrl, retryAfterMillis)
                        }
                        if (isCloudflareBlocked(statusCode, body)) {
                            val reason = "Linovelib request was blocked (HTTP $statusCode): $requestUrl"
                            LinovelibRateLimiter.coolDown(
                                reason = reason,
                                delayMillis = maxOf(
                                    retryAfterMillis ?: 0L,
                                    LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS
                                ),
                                blocked = true
                            )
                            throw LinovelibBlockedException(reason)
                        }
                        if (statusCode !in 200..299) {
                            if (statusCode >= 500 && retryAfterMillis != null) {
                                LinovelibRateLimiter.coolDown("HTTP $statusCode $requestUrl", retryAfterMillis)
                            }
                            throw LinovelibHttpException(statusCode, requestUrl, retryAfterMillis)
                        }
                        body
                    }
                    val nextUrl = redirectUrl ?: return@withContext body
                    requestUrl = nextUrl
                    redirectCount++
                }
            } catch (e: SocketTimeoutException) {
                lastError = e
            } catch (e: IOException) {
                lastError = e
                if (!shouldRetry(e)) throw e
            } catch (e: Throwable) {
                throw e
            }
            if (attempt < retryTime) {
                val retryDelay = retryDelayMillis(delayMillis)
                Log.w(TAG, "request failed, retrying in ${retryDelay}ms: $url", lastError)
                delay(retryDelay.milliseconds)
                delayMillis = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
        throw lastError ?: IOException("Linovelib request failed: $url")
    }

    private fun shouldRetry(error: Throwable?): Boolean = when (error) {
        is LinovelibBlockedException -> false
        is LinovelibHttpException -> error.statusCode >= 500 && error.retryAfterMillis == null
        else -> true
    }

    private fun retryDelayMillis(currentDelayMillis: Long): Long {
        // 这里只限制客户端自己的网络错误退避；服务器冷却由共享 limiter 保留并快速失败。
        val cappedBaseDelay = currentDelayMillis.coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        val jitterBound = (MAX_RETRY_DELAY_MILLIS - cappedBaseDelay).coerceIn(0L, RETRY_JITTER_MILLIS)
        return cappedBaseDelay + if (jitterBound > 0L) Random.nextLong(jitterBound + 1) else 0L
    }

    companion object {
        private const val TAG = "LinovelibJsoup"
        private const val MAX_RETRY_DELAY_MILLIS = 30_000L
        private const val RETRY_JITTER_MILLIS = 1_000L
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

        internal fun resolveDesktopRedirect(currentUrl: String, location: String): String {
            val target = runCatching { URI(currentUrl).resolve(location.trim()).toString() }.getOrNull()
            if (location.isBlank() || target == null || !LinovelibConstants.isDesktopPageUrl(target)) {
                throw LinovelibBlockedException("Linovelib 后台请求被重定向到非电脑端地址，请使用网页浏览处理")
            }
            return target
        }

        fun isCloudflareBlocked(statusCode: Int, body: String): Boolean {
            if (statusCode == 403 || statusCode == 503) return true
            val text = body.lowercase()
            if (("cloudflare" in text && (
                    "cf-chl" in text ||
                        "checking your browser" in text ||
                        "just a moment" in text ||
                        "attention required" in text
                    )) || "请稍候" in body && "正在检查您的浏览器" in body
            ) return true

            val document = Jsoup.parse(body)
            if (document.selectFirst(
                    "#challenge-running, #challenge-stage, .cf-turnstile, " +
                        "iframe[src*=challenges.cloudflare.com]"
                ) != null
            ) return true
            // 真正的失效登录页仍允许章节层匿名回退，不能与独立验证页混为一谈。
            if (document.selectFirst("input[type=password], form[action*=login], .login-form") != null) {
                return false
            }
            val title = document.title().lowercase()
            val pageText = document.text()
            return title.contains("verify") || title.contains("verification") ||
                title.contains("安全验证") || title.contains("安全驗證") ||
                document.selectFirst(".verify, #verify, .verification") != null ||
                pageText.length < 3_000 && (
                    pageText.contains("验证后继续") ||
                        pageText.contains("checking your browser", ignoreCase = true)
                    )
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

        fun normalizeCoverUrl(url: String): String {
            val normalizedUrl = normalizeUrl(url)
            if (normalizedUrl.isBlank()) return ""
            val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return normalizedUrl
            val host = uri.host?.lowercase() ?: return normalizedUrl
            val path = uri.rawPath.orEmpty()
            if (!path.startsWith("/files/article/image/")) return normalizedUrl

            val desktopHost = URI(LinovelibConstants.BASE_URL).host.lowercase()
            val mobileHost = URI(LinovelibConstants.MOBILE_BASE_URL).host.lowercase()
            val mobileRootDomain = mobileHost.substringAfter('.')
            val isLinovelibHost = host == desktopHost ||
                host == mobileHost ||
                host.endsWith(".$mobileRootDomain")
            if (!isLinovelibHost) return normalizedUrl

            return buildString {
                append(LinovelibConstants.BASE_URL)
                append(path)
                uri.rawQuery?.let { append('?').append(it) }
                uri.rawFragment?.let { append('#').append(it) }
            }
        }
    }
}

internal fun sanitizeLinovelibRequestCookie(cookie: String): String = cookie
    .split(';')
    .map(String::trim)
    .filter { it.isNotBlank() && '=' in it }
    .filterNot { value ->
        val name = value.substringBefore('=').trim().lowercase()
        name.startsWith("cf_") || name.startsWith("__cf") || name == "_cfuvid"
    }
    .joinToString("; ")
