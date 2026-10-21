package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibRateLimiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** 一次真实电脑端筛选页加载；标识符隔离筛选切换、重试及过期 WebView 回调。 */
data class LinovelibFilterWebRequest(val id: Long, val url: String)

internal interface LinovelibFilterBrowserDataSource {
    val filterDocumentLoader: LinovelibFilterDocumentLoader?
}

/**
 * 只把 Expanded 页面正在等待的文档交给其 WebView 宿主，不自行请求或重试。
 * 文档仍交由原有筛选 parser/cache 处理，列表不需要逐本加载详情。
 */
internal class LinovelibFilterDocumentLoader {
    private data class Pending(
        val request: LinovelibFilterWebRequest,
        val response: CompletableDeferred<Document>
    )

    private val mutex = Mutex()
    private val stateLock = Any()
    private var pending: Pending? = null
    private val mutableRequests = MutableStateFlow<LinovelibFilterWebRequest?>(null)
    val requests: StateFlow<LinovelibFilterWebRequest?> = mutableRequests.asStateFlow()

    suspend fun loadDocument(url: String): Document = mutex.withLock {
        check(LinovelibConstants.isDesktopPageUrl(url) && LinovelibFilterUrl.parse(url) != null)
        val load = LinovelibRateLimiter.run(ignoreFailureCooldown = true) {
            synchronized(stateLock) {
                Pending(LinovelibFilterWebRequest(nextRequestId.incrementAndGet(), url), CompletableDeferred()).also {
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

    suspend fun acceptPage(requestId: Long, url: String, html: String): Boolean {
        val load = synchronized(stateLock) { pending?.takeIf { it.request.id == requestId } }
            ?: return false
        if (!LinovelibConstants.isDesktopPageUrl(url) ||
            LinovelibFilterUrl.parse(url) != LinovelibFilterUrl.parse(load.request.url)
        ) return false

        val document = withContext(Dispatchers.Default) {
            Jsoup.parse(html, url).also { document ->
                // 普通错误页、尚未完成的 DOM 不能被缓存为“无结果”。保留注释内出版社信息。
                val page = LinovelibFilterPageParser.parse(document, url)
                check(page.filters.isNotEmpty()) { "Linovelib 筛选网页没有可识别的筛选条件，请重试" }
            }
        }
        return synchronized(stateLock) {
            pending === load && load.response.complete(document)
        }
    }

    fun fail(requestId: Long, message: String) {
        synchronized(stateLock) {
            pending?.takeIf { it.request.id == requestId }
                ?.response?.completeExceptionally(IOException(message))
        }
    }

    private companion object {
        val nextRequestId = AtomicLong()
    }
}
