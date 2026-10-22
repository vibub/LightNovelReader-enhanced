package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibHttpException
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** 一次移动筛选网页加载；标识符隔离筛选切换、重试及过期 WebView 回调。 */
data class LinovelibFilterWebRequest(
    val id: Long,
    val url: String,
    val requiresVerification: Boolean
)

internal interface LinovelibFilterBrowserDataSource {
    val filterDocumentLoader: LinovelibFilterDocumentLoader?
}

/** 移动 HTTP 优先；验证后本次筛选继续使用 WebView 文档，不转运其 Cookie 或自动重试 HTTP。 */
internal class LinovelibFilterDocumentLoader(private val jsoup: LinovelibJsoup) {
    private data class Pending(
        val request: LinovelibFilterWebRequest,
        val response: CompletableDeferred<Document>
    )

    private class VerificationRequired : IOException()

    private val mutex = Mutex()
    private val stateLock = Any()
    private var pending: Pending? = null
    private var preferBrowser = false
    private val mutableRequests = MutableStateFlow<LinovelibFilterWebRequest?>(null)
    val requests: StateFlow<LinovelibFilterWebRequest?> = mutableRequests.asStateFlow()

    suspend fun loadDocument(url: String): Document = mutex.withLock {
        check(LinovelibConstants.isFilterSiteUrl(url) && LinovelibFilterUrl.parse(url) != null)
        val useBrowser = synchronized(stateLock) { preferBrowser }
        if (!useBrowser) {
            try {
                return@withLock loadHttpDocument(url)
            } catch (_: VerificationRequired) {
                // 只有真实验证响应才请求用户确认；网络错误、普通 403/503 或未知 DOM 直接报错。
            }
        }
        val load = LinovelibRateLimiter.run(ignoreFailureCooldown = true) {
            synchronized(stateLock) {
                Pending(
                    LinovelibFilterWebRequest(nextRequestId.incrementAndGet(), url, !useBrowser),
                    CompletableDeferred()
                ).also {
                    pending = it
                    mutableRequests.value = it.request
                }
            }
        }
        try {
            load.response.await()
        } finally {
            synchronized(stateLock) {
                if (pending === load) {
                    pending = null
                    mutableRequests.value = null
                }
            }
            load.response.cancel()
        }
    }

    private suspend fun loadHttpDocument(url: String): Document = withContext(Dispatchers.IO) {
        // 独立公共会话，不读账号 Cookie，也不读取 WebView 的验证 Cookie。
        val session = Jsoup.newSession()
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(false)
            .timeout(15_000)
            .headers(jsoup.defaultHeaders(
                referer = LinovelibConstants.FILTER_BASE_URL,
                useCookie = false,
                userAgentMode = LinovelibJsoup.UserAgentMode.Mobile
            ) + ("Accept" to "text/html,application/xhtml+xml"))
        var requestUrl = url
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            currentCoroutineContext().ensureActive()
            check(LinovelibConstants.isFilterSiteUrl(requestUrl))
            val document = LinovelibRateLimiter.run(ignoreFailureCooldown = true) {
                val response = session.newRequest().url(requestUrl).execute()
                currentCoroutineContext().ensureActive()
                val responseUrl = response.url().toString()
                if (!LinovelibConstants.isFilterSiteUrl(responseUrl)) {
                    throw IOException("筛选请求离开了移动站点，已停止加载")
                }
                val status = response.statusCode()
                if (status in REDIRECT_STATUS_CODES) {
                    val location = response.header("Location").orEmpty()
                    val target = runCatching { URI(responseUrl).resolve(location.trim()).toString() }.getOrNull()
                    if (redirectCount == MAX_REDIRECTS || location.isBlank() || target == null ||
                        !LinovelibConstants.isFilterSiteUrl(target)
                    ) throw IOException("筛选请求重定向异常，未自动切换站点")
                    requestUrl = target
                    null
                } else {
                    val retryAfterMillis = LinovelibJsoup.parseRetryAfterMillis(response.header("Retry-After"))
                    val page = response.parse()
                    if (response.header("cf-mitigated").equals("challenge", ignoreCase = true) ||
                        page.isSecurityVerificationPage()
                    ) {
                        LinovelibRateLimiter.coolDown(
                            reason = "移动筛选需要网页验证",
                            delayMillis = maxOf(retryAfterMillis ?: 0L, LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS),
                            blocked = true
                        )
                        throw VerificationRequired()
                    }
                    if (status == 429) {
                        LinovelibRateLimiter.coolDown(
                            reason = "HTTP 429 移动筛选",
                            delayMillis = retryAfterMillis ?: LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS
                        )
                    } else if (status >= 500 && retryAfterMillis != null) {
                        LinovelibRateLimiter.coolDown("HTTP $status 移动筛选", retryAfterMillis)
                    }
                    if (status !in 200..299) throw LinovelibHttpException(status, url, retryAfterMillis)
                    if (LinovelibFilterUrl.parse(responseUrl) != LinovelibFilterUrl.parse(url)) {
                        throw IOException("移动筛选返回了其他页面，请重试")
                    }
                    validateDocument(page, responseUrl)
                    page
                }
            }
            if (document != null) return@withContext document
        }
        throw IOException("筛选请求重定向次数过多")
    }

    suspend fun acceptPage(requestId: Long, url: String, html: String): Boolean {
        val load = synchronized(stateLock) { pending?.takeIf { it.request.id == requestId } }
            ?: return false
        if (!LinovelibConstants.isFilterSiteUrl(url) ||
            LinovelibFilterUrl.parse(url) != LinovelibFilterUrl.parse(load.request.url)
        ) return false

        val document = withContext(Dispatchers.Default) {
            Jsoup.parse(html, url).also { validateDocument(it, url) }
        }
        return synchronized(stateLock) {
            if (pending !== load || load.response.isCompleted) return@synchronized false
            preferBrowser = true
            load.response.complete(document)
        }
    }

    fun fail(requestId: Long, message: String) {
        synchronized(stateLock) {
            pending?.takeIf { it.request.id == requestId }
                ?.response?.completeExceptionally(IOException(message))
        }
    }

    fun resetSession() {
        synchronized(stateLock) {
            preferBrowser = false
            pending?.response?.cancel()
            pending = null
            mutableRequests.value = null
        }
    }

    private fun validateDocument(document: Document, url: String) {
        // 保留原始 HTML 注释内的出版社；错误页或未知书单结构不能进入结果缓存。
        val page = LinovelibFilterPageParser.parse(document, url)
        check(page.filters.isNotEmpty()) { "Linovelib 筛选网页没有可识别的筛选条件，请重试" }
    }

    private companion object {
        val nextRequestId = AtomicLong()
        const val MAX_REDIRECTS = 5
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}
