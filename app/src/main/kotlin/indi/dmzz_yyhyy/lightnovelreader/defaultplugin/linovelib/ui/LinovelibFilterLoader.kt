package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.explore.LinovelibFilterWebRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

/** HTTP 确认遇到验证后才启动；验证后的筛选、翻页继续复用本页的同一 WebView。 */
@Composable
fun LinovelibFilterLoader(
    request: LinovelibFilterWebRequest?,
    onPageReady: suspend (requestId: Long, url: String, html: String) -> Boolean,
    onFailure: (requestId: Long, message: String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var browserUrl by remember { mutableStateOf<String?>(null) }
    var loadedRequestId by remember { mutableStateOf<Long?>(null) }
    var showBrowser by remember(request?.id) { mutableStateOf(false) }
    var showVerificationDialog by remember(request?.id) {
        mutableStateOf(request?.requiresVerification == true)
    }
    var verificationApproved by remember(request?.id) { mutableStateOf(false) }
    var waitingForVerification by remember(request?.id) { mutableStateOf(false) }
    var pageLoading by remember(request?.id) { mutableStateOf(true) }
    var httpError by remember(request?.id) { mutableStateOf<String?>(null) }
    var finished by remember(request?.id) { mutableStateOf(false) }
    var attempt by remember(request?.id) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val verificationCancelledMessage = stringResource(R.string.linovelib_verification_cancelled)
    val verificationTimeoutMessage = stringResource(R.string.linovelib_verification_timeout)
    val currentRequest by rememberUpdatedState(request)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val fail: (String) -> Unit = { message ->
        if (request != null && !finished) {
            finished = true
            showBrowser = false
            showVerificationDialog = false
            webView?.stopLoading()
            browserUrl = null
            loadedRequestId = null
            currentOnFailure(request.id, message)
        }
    }

    BackHandler(enabled = request != null && showBrowser) { fail(verificationCancelledMessage) }
    DisposableEffect(Unit) {
        onDispose {
            // 离开页面时结束等待并销毁；请求之间则保留同一浏览器及其站点会话。
            currentRequest?.let { currentOnFailure(it.id, "筛选网页已关闭，请重试") }
            webView?.run {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
    LaunchedEffect(request?.id) {
        if (request != null && !request.requiresVerification && browserUrl == null) {
            // 浏览器通道已获确认；失败释放或配置变化后，手动重试仍能重新创建宿主。
            loadedRequestId = request.id
            browserUrl = request.url
        }
    }
    LaunchedEffect(request?.id, webView, showVerificationDialog) {
        val view = webView ?: return@LaunchedEffect
        if (request == null) {
            view.stopLoading()
        } else if (!showVerificationDialog && !finished && loadedRequestId != request.id) {
            view.stopLoading()
            loadedRequestId = request.id
            view.loadUrl(request.url, mapOf("Referer" to LinovelibConstants.FILTER_BASE_URL))
        }
    }
    LaunchedEffect(webView, showBrowser) {
        webView?.apply {
            visibility = View.VISIBLE
            isFocusable = showBrowser
            isFocusableInTouchMode = showBrowser
            importantForAccessibility = if (showBrowser) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (!showBrowser) clearFocus()
        }
    }
    LaunchedEffect(request?.id, webView, attempt, waitingForVerification, showVerificationDialog) {
        if (request == null || webView == null || waitingForVerification || showVerificationDialog || finished) {
            return@LaunchedEffect
        }
        delay(30.seconds)
        fail("筛选网页加载超时，未自动重试，请重试")
    }
    LaunchedEffect(request?.id, webView, attempt, verificationApproved) {
        if (request == null || webView == null || !verificationApproved || finished) return@LaunchedEffect
        // 从用户确认起独立计时，不因验证页自身的导航反复重置。
        delay(120.seconds)
        fail(verificationTimeoutMessage)
    }
    LaunchedEffect(request?.id, webView, lifecycleOwner, attempt, loadedRequestId) {
        val view = webView ?: return@LaunchedEffect
        val activeRequest = request ?: return@LaunchedEffect
        if (loadedRequestId != activeRequest.id) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!finished) {
                if (showVerificationDialog) {
                    delay(1.seconds)
                    continue
                }
                val snapshot = withTimeoutOrNull(3.seconds) { view.filterPageSnapshot() }
                val page = snapshot?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                if (page?.get("blocked")?.jsonPrimitive?.booleanOrNull == true ||
                    page?.get("challengePending")?.jsonPrimitive?.booleanOrNull == true
                ) {
                    waitingForVerification = true
                    if (verificationApproved) showBrowser = true else showVerificationDialog = true
                    pageLoading = false
                    httpError = null
                } else if (!pageLoading && page?.get("ready")?.jsonPrimitive?.booleanOrNull == true) {
                    waitingForVerification = false
                    val error = httpError
                    val url = page["url"]?.jsonPrimitive?.contentOrNull
                    val html = page["html"]?.jsonPrimitive?.contentOrNull
                    if (error != null) {
                        fail(error)
                    } else if (url != null && html != null) {
                        try {
                            if (currentOnPageReady(activeRequest.id, url, html)) {
                                finished = true
                                showBrowser = false
                            } else {
                                fail("筛选网页地址已变化，请重试")
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            fail(error.message ?: "无法识别筛选网页，请重试")
                        }
                    } else {
                        fail("未能读取移动端筛选网页，请重试")
                    }
                }
                if (!finished) delay(1.seconds)
            }
        }
    }

    browserUrl?.let { initialUrl ->
        DisposableEffect(Unit) {
            onDispose {
                webView?.run {
                    stopLoading()
                    destroy()
                }
                webView = null
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize()
                .zIndex(if (showBrowser) 1f else -1f)
                .graphicsLayer { alpha = if (showBrowser) 1f else 0f },
            topBar = {
                if (showBrowser) {
                    TopAppBar(
                        title = { Text("筛选网页验证") },
                        navigationIcon = { LinovelibWebCloseButton { fail(verificationCancelledMessage) } },
                        actions = {
                            TextButton(
                                enabled = !pageLoading && !finished,
                                onClick = {
                                    request?.let { activeRequest ->
                                        attempt++
                                        waitingForVerification = false
                                        httpError = null
                                        pageLoading = true
                                        webView?.run {
                                            stopLoading()
                                            loadUrl(activeRequest.url, mapOf("Referer" to LinovelibConstants.FILTER_BASE_URL))
                                        }
                                    }
                                }
                            ) { Text(stringResource(R.string.retry)) }
                        }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (showBrowser) {
                    Text(
                        text = "请在下方网页中手动完成站点验证，识别到本次筛选结果后将自动返回列表。",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LinovelibWebView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    initialUrl = initialUrl,
                    mode = LinovelibWebViewMode.MobileFilter,
                    onWebViewCreated = { view ->
                        view.visibility = View.VISIBLE
                        view.isFocusable = showBrowser
                        view.isFocusableInTouchMode = showBrowser
                        view.importantForAccessibility = if (showBrowser) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        webView = view
                    },
                    onPageStarted = { _, _ ->
                        if (request != null && loadedRequestId == request.id && !finished) {
                            pageLoading = true
                            waitingForVerification = false
                            httpError = null
                        }
                    },
                    onPageFinished = { _, _ ->
                        if (request != null && loadedRequestId == request.id) pageLoading = false
                    },
                    onPageError = { code, _ ->
                        if (request != null && loadedRequestId == request.id && !finished) {
                            val message = "筛选网页加载失败（错误码 $code），未自动重试"
                            if (code > 0) httpError = message else fail(message)
                        }
                    }
                )
            }
        }
    }

    if (showVerificationDialog && request != null && !finished) {
        LinovelibVerificationDialog(
            onConfirm = {
                verificationApproved = true
                showVerificationDialog = false
                showBrowser = true
                if (browserUrl == null) {
                    // 首次 HTTP 验证经用户确认后才创建浏览器；已有浏览器只展示，不刷新当前验证页。
                    loadedRequestId = request.id
                    browserUrl = request.url
                }
            },
            onDismiss = { fail(verificationCancelledMessage) }
        )
    }
}

private suspend fun WebView.filterPageSnapshot(): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(FILTER_PAGE_SNAPSHOT_SCRIPT) { value ->
        if (continuation.isActive) continuation.resume(value)
    }
}

private val FILTER_PAGE_SNAPSHOT_SCRIPT = $$"""
(function() {
    if (!document.body) return null;
    var ready = document.readyState === 'complete';
    if (location.origin !== $${Json.encodeToString(LinovelibConstants.FILTER_BASE_URL)}) return {ready: ready};
    var blocked = Array.prototype.some.call(document.querySelectorAll(
        '#challenge-running, #cf-challenge-running, #challenge-stage, .cf-turnstile, ' +
        'iframe[src*="challenges.cloudflare.com"], form[action*="/cdn-cgi/challenge-platform/"], ' +
        'input[name*="captcha"], input[id*="captcha"], img[src*="captcha"]'
    ), function(node) {
        var rect = node.getBoundingClientRect();
        var style = getComputedStyle(node);
        return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
    });
    if (blocked) return {blocked: true};
    if (!ready) return null;
    var challengePending = !!document.querySelector('.main-wrapper .main-content') &&
        typeof window._cf_chl_opt === 'object' &&
        !!document.querySelector('script[src*="/cdn-cgi/challenge-platform/"]') &&
        !document.querySelector('#filters');
    if (challengePending) return {ready: true, challengePending: true};
    if (!/^\/wenku\/[a-z]+_(?:[\d-]+_){8}[\d-]+\.html$/i.test(location.pathname)) return {ready: true};
    var page = document.documentElement.cloneNode(true);
    page.querySelectorAll('script, style, input, textarea, iframe').forEach(function(node) { node.remove(); });
    return {url: location.href, html: page.outerHTML, ready: true, blocked: false};
})()
"""
