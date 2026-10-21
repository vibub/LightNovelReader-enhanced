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

/** 原生筛选页的文档宿主；轮询只读取 DOM，任何一次加载都不会自动刷新或重放网络请求。 */
@Composable
fun LinovelibFilterLoader(
    request: LinovelibFilterWebRequest,
    onPageReady: suspend (url: String, html: String) -> Boolean,
    onFailure: (String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var waitingForVerification by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var httpError by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val fail: (String) -> Unit = { message ->
        if (!finished) {
            finished = true
            webView?.stopLoading()
            currentOnFailure(message)
        }
    }

    BackHandler(enabled = showBrowser) { fail("已取消筛选网页验证，请重试") }
    DisposableEffect(request.id) {
        onDispose {
            // 配置变化或离开页面都必须结束等待；旧 ID 不会取消后续的筛选请求。
            if (!finished) currentOnFailure("筛选网页已关闭，请重试")
            webView?.run {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
    LaunchedEffect(webView, showBrowser) {
        webView?.apply {
            // 隐藏 Compose 呈现而非网页本身，保持正常网页脚本和渲染行为。
            visibility = View.VISIBLE
            isFocusable = showBrowser
            isFocusableInTouchMode = showBrowser
            importantForAccessibility = if (showBrowser) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (!showBrowser) clearFocus()
        }
    }
    LaunchedEffect(webView, attempt, waitingForVerification) {
        if (webView == null || waitingForVerification || finished) return@LaunchedEffect
        delay(30.seconds)
        fail("筛选网页加载超时，未自动重试，请重试")
    }
    LaunchedEffect(webView, lifecycleOwner, attempt) {
        val view = webView ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (!finished) {
                val snapshot = withTimeoutOrNull(3.seconds) { view.filterPageSnapshot() }
                val page = snapshot?.let {
                    runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
                }
                if (page?.get("blocked")?.jsonPrimitive?.booleanOrNull == true ||
                    page?.get("challengePending")?.jsonPrimitive?.booleanOrNull == true
                ) {
                    // 已确认的验证文档也需保留，等待站点脚本插入控件，不能提前交给结果解析器。
                    waitingForVerification = true
                    showBrowser = true
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
                            if (currentOnPageReady(url, html)) {
                                finished = true
                            } else {
                                fail("筛选网页地址已变化，请重试")
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            fail(error.message ?: "无法识别筛选网页，请重试")
                        }
                    } else {
                        fail("未能读取电脑端筛选网页，请重试")
                    }
                }
                if (!finished) delay(1.seconds)
            }
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
                    navigationIcon = { LinovelibWebCloseButton { fail("已取消筛选网页验证，请重试") } },
                    actions = {
                        TextButton(
                            enabled = !pageLoading && !finished,
                            onClick = {
                                // 用户主动重试不经过应用冷却，也不自动循环。
                                attempt++
                                waitingForVerification = false
                                httpError = null
                                pageLoading = true
                                webView?.run {
                                    stopLoading()
                                    loadUrl(request.url)
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
                initialUrl = request.url,
                mode = LinovelibWebViewMode.DesktopBrowsing,
                onWebViewCreated = { view ->
                    view.visibility = View.VISIBLE
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    view.settings.blockNetworkImage = false
                    view.settings.loadsImagesAutomatically = true
                    webView = view
                },
                onPageStarted = { _, _ ->
                    pageLoading = true
                    waitingForVerification = false
                    httpError = null
                },
                onPageFinished = { _, _ -> pageLoading = false },
                onPageError = { code, _ ->
                    val message = "筛选网页加载失败（错误码 $code），未自动重试"
                    if (code > 0) {
                        // 先读取错误响应的 DOM，让真实验证页仍然可以人工操作。
                        httpError = message
                    } else {
                        fail(message)
                    }
                }
            )
        }
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
    if (location.origin !== $${Json.encodeToString(LinovelibConstants.BASE_URL)}) return {ready: ready};
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
