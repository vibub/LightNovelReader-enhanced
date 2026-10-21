package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibRateLimiter
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibWebSearchRequest
import indi.dmzz_yyhyy.lightnovelreader.ui.components.EmptyPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibSearchLoader(
    request: LinovelibWebSearchRequest,
    showBrowser: Boolean,
    onShowBrowser: () -> Unit,
    onPageReady: suspend (url: String, html: String) -> Boolean,
    onCancel: () -> Unit,
    onOpenMobileSearch: () -> Unit,
    onBookDetected: (String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var lastBookUrl by remember { mutableStateOf<String?>(null) }
    var searchSubmitted by remember { mutableStateOf(false) }
    var searchAttempt by remember { mutableStateOf(0) }
    var submissionInProgress by remember { mutableStateOf(false) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var httpError by remember { mutableStateOf<String?>(null) }
    var verificationPaused by remember { mutableStateOf(false) }
    var cooldownMillis by remember { mutableStateOf(LinovelibRateLimiter.remainingCooldownMillis()) }
    val context = LocalContext.current
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnShowBrowser by rememberUpdatedState(onShowBrowser)
    val currentOnBookDetected by rememberUpdatedState(onBookDetected)
    val lifecycleOwner = LocalLifecycleOwner.current
    val initialUrl = LinovelibConstants.BASE_URL
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    val showLoadError: (String) -> Unit = { message ->
        pageLoading = false
        pageError = message
        currentOnShowBrowser()
    }
    val cooldownMessage: () -> String = {
        context.getString(R.string.linovelib_search_cooldown, cooldownMillis / 1_000 + 1)
    }
    val retrySearch: () -> Unit = {
        cooldownMillis = LinovelibRateLimiter.remainingCooldownMillis()
        if (cooldownMillis > 0) {
            showLoadError(cooldownMessage())
        } else if (!pageLoading) {
            webView?.let { view ->
                pageError = null
                httpError = null
                searchSubmitted = false
                searchAttempt++
                submissionInProgress = false
                pageLoading = true
                view.stopLoading()
                // 从首页重新走站内表单，不能 reload 空白文档或重放上一次 POST。
                view.loadUrl(initialUrl)
            }
        }
    }
    val submitSearch: (WebView) -> Unit = { view ->
        if (!searchSubmitted && !submissionInProgress && !verificationPaused &&
            pageError == null && httpError == null
        ) {
            cooldownMillis = LinovelibRateLimiter.remainingCooldownMillis()
            if (cooldownMillis > 0) {
                showLoadError(cooldownMessage())
            } else {
                submissionInProgress = true
                view.submitLinovelibDesktopSearch(request.keyword) { submitted ->
                    submissionInProgress = false
                    if (submitted) searchSubmitted = true
                }
            }
        }
    }

    BackHandler(enabled = showBrowser) {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onCancel()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.run {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    LaunchedEffect(webView, showBrowser) {
        webView?.apply {
            visibility = if (showBrowser) View.VISIBLE else View.INVISIBLE
            isFocusable = showBrowser
            isFocusableInTouchMode = showBrowser
            // 只隐藏呈现层，不暂停页面执行；显示后重排并重绘，不重新发网络请求。
            settings.blockNetworkImage = !showBrowser
            if (showBrowser) {
                requestLayout()
                invalidate()
            } else {
                clearFocus()
            }
        }
    }
    LaunchedEffect(webView, pageLoading) {
        val view = webView ?: return@LaunchedEffect
        if (pageLoading) {
            delay(30_000)
            view.stopLoading()
            showLoadError(context.getString(R.string.linovelib_search_load_error, -8))
        }
    }
    LaunchedEffect(request.id) {
        delay(12_000)
        currentOnShowBrowser()
    }
    LaunchedEffect(webView, lifecycleOwner, searchAttempt) {
        val view = webView ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSnapshot: String? = null
            var blankSnapshots = 0
            while (true) {
                cooldownMillis = LinovelibRateLimiter.remainingCooldownMillis()
                if (pageError != null) {
                    delay(1_000)
                    continue
                }
                val snapshot = withTimeoutOrNull(3_000) { view.searchPageSnapshot() }
                val page = snapshot?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                if (!pageLoading && page?.get("blank")?.jsonPrimitive?.booleanOrNull == true) {
                    blankSnapshots++
                    if (blankSnapshots >= 2) {
                        showLoadError(httpError ?: context.getString(R.string.linovelib_search_blank_page))
                    }
                } else {
                    blankSnapshots = 0
                }
                if (snapshot != null && snapshot != lastSnapshot && pageError == null) {
                    lastSnapshot = snapshot
                    if (page?.get("blocked")?.jsonPrimitive?.booleanOrNull == true) {
                        if (!verificationPaused) {
                            verificationPaused = true
                            LinovelibRateLimiter.coolDown("网页需要手动验证", 60_000, blocked = true)
                        }
                        pageLoading = false
                        httpError = null
                        currentOnShowBrowser()
                    } else if (httpError != null) {
                        showLoadError(httpError!!)
                    } else {
                        val url = page?.get("url")?.jsonPrimitive?.contentOrNull
                        val html = page?.get("html")?.jsonPrimitive?.contentOrNull
                        if (url != null && html != null && currentOnPageReady(url, html)) return@repeatOnLifecycle
                        if (!searchSubmitted && page?.get("formReady")?.jsonPrimitive?.booleanOrNull == true) {
                            submitSearch(view)
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    // 保持同一个 WebView 在后台加载，普通搜索只显示共用搜索页的原生动画。
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (showBrowser) 1f else -1f)
            .graphicsLayer { alpha = if (showBrowser) 1f else 0f },
        topBar = {
            if (showBrowser) {
                TopAppBar(
                    title = { Text(stringResource(R.string.linovelib_web_search_title)) },
                    navigationIcon = { LinovelibWebCloseButton(onCancel) },
                    actions = {
                        LinovelibWebNavigationActions(
                            webView = webView,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            updateNavigationState = updateNavigationState,
                            onReload = retrySearch,
                            reloadEnabled = !pageLoading && cooldownMillis == 0L
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showBrowser) {
                Text(
                    text = stringResource(R.string.linovelib_search_verification_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box(Modifier.weight(1f)) {
                LinovelibWebView(
                    modifier = Modifier.fillMaxSize(),
                    initialUrl = if (cooldownMillis > 0) "about:blank" else initialUrl,
                    mode = LinovelibWebViewMode.DesktopBrowsing,
                    onWebViewCreated = { view ->
                        view.visibility = if (showBrowser) View.VISIBLE else View.INVISIBLE
                        view.isFocusable = showBrowser
                        view.isFocusableInTouchMode = showBrowser
                        view.settings.blockNetworkImage = !showBrowser
                        webView = view
                        if (cooldownMillis > 0) showLoadError(cooldownMessage())
                    },
                    onUrlChanged = { url ->
                        if (url != lastBookUrl) {
                            lastBookUrl = url
                            LinovelibConstants.extractBookIdFromUrl(url)?.let(currentOnBookDetected)
                        }
                    },
                    onPageStarted = { _, url ->
                        if (url == "about:blank" && cooldownMillis > 0) {
                            showLoadError(cooldownMessage())
                        } else {
                            pageLoading = true
                            pageError = null
                            httpError = null
                            verificationPaused = false
                        }
                    },
                    onPageFinished = { view, _ ->
                        pageLoading = false
                        updateNavigationState(view)
                    },
                    onPageError = { code, retryAfter ->
                        pageLoading = false
                        val message = context.getString(R.string.linovelib_search_load_error, code)
                        if (code in listOf(403, 429, 503)) {
                            val requestedDelay = LinovelibJsoup.parseRetryAfterMillis(retryAfter)
                            val pause = if (code == 429) requestedDelay ?: 60_000 else maxOf(requestedDelay ?: 0, 60_000)
                            LinovelibRateLimiter.coolDown("网页 HTTP $code", pause, blocked = code != 429)
                            cooldownMillis = LinovelibRateLimiter.remainingCooldownMillis()
                            verificationPaused = true
                        }
                        if (code > 0) {
                            // 403 也可能带有可操作的验证页，先检查 DOM 再决定是否显示错误。
                            httpError = message
                            currentOnShowBrowser()
                        } else {
                            showLoadError(message)
                        }
                    }
                )
                if (showBrowser && pageLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (showBrowser && pageError != null) {
                    Surface(Modifier.fillMaxSize()) {
                        EmptyPage(
                            icon = painterResource(R.drawable.error_24px),
                            title = stringResource(R.string.linovelib_search_load_failed),
                            description = if (cooldownMillis > 0) cooldownMessage() else pageError.orEmpty()
                        ) {
                            TextButton(
                                enabled = !pageLoading && cooldownMillis == 0L,
                                onClick = retrySearch
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            TextButton(onClick = onOpenMobileSearch) {
                                Text(stringResource(R.string.linovelib_search_open_mobile))
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun WebView.submitLinovelibDesktopSearch(keyword: String, onSubmitted: (Boolean) -> Unit) {
    if (keyword.isBlank() || !LinovelibConstants.isDesktopPageUrl(url.orEmpty())) {
        onSubmitted(false)
        return
    }
    // 从电脑端首页读取原有表单，保留站点的提交方式、隐藏字段及事件处理。
    evaluateJavascript(
        """
        (function() {
            if (location.origin !== ${Json.encodeToString(LinovelibConstants.BASE_URL)}) return false;
            var searchPath = /^(?:\/search\.(?:html|php)|\/modules\/article\/search\.php|\/S\d+\/?)${'$'}/;
            var input = document.querySelector('form input[name="searchkey"]');
            if (!input) {
                if (!['/', '/index.php', '/index.html'].includes(location.pathname)) return false;
                var link = Array.from(document.querySelectorAll('a[href]')).find(function(a) {
                    var target = new URL(a.href, location.href);
                    return target.origin === location.origin && searchPath.test(target.pathname);
                });
                if (link) link.click();
                return false;
            }
            var form = input.form;
            var action = new URL(form.action, location.href);
            if (action.origin !== location.origin || !searchPath.test(action.pathname)) return false;
            if (form.method.toLowerCase() !== 'post' && form.method.toLowerCase() !== 'get') return false;
            if (form.dataset.lnrSearchSubmitted === 'true') return true;
            form.dataset.lnrSearchSubmitted = 'true';
            input.value = ${Json.encodeToString(keyword)};
            input.dispatchEvent(new Event('input', {bubbles: true}));
            if (form.requestSubmit) form.requestSubmit();
            else if (form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}))) form.submit();
            return true;
        })()
        """.trimIndent()
    ) { onSubmitted(it == "true") }
}

internal fun WebView.submitLinovelibSearch(keyword: String, onSubmitted: (Boolean) -> Unit) {
    // 先走站内入口，避免缺少首页会话初始化时搜索页返回空白内容。
    if (url?.substringBefore('?')?.substringBefore('#')?.trimEnd('/') == LinovelibConstants.FILTER_BASE_URL) {
        evaluateJavascript(
            """
            (function() {
                var link = document.querySelector('a.search-guide');
                if (link && new URL(link.href).origin === location.origin) link.click();
            })()
            """.trimIndent(),
            null
        )
        onSubmitted(false)
        return
    }
    if (keyword.isBlank() || !LinovelibConstants.isSearchPageUrl(url.orEmpty())) {
        onSubmitted(false)
        return
    }
    evaluateJavascript(
        """
        (function() {
            var input = document.querySelector('form input[name="searchkey"]');
            if (!input || input.form.method.toLowerCase() !== 'post') return false;
            var form = input.form;
            if (new URL(form.action, location.href).origin !== location.origin) return false;
            if (form.dataset.lnrSearchSubmitted === 'true') return true;
            form.dataset.lnrSearchSubmitted = 'true';
            input.value = ${Json.encodeToString(keyword)};
            input.dispatchEvent(new Event('input', {bubbles: true}));
            if (form.requestSubmit) form.requestSubmit();
            else if (form.dispatchEvent(new Event('submit', {bubbles: true, cancelable: true}))) form.submit();
            return true;
        })()
        """.trimIndent()
    ) { onSubmitted(it == "true") }
}

private suspend fun WebView.searchPageSnapshot(): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(SEARCH_PAGE_SNAPSHOT_SCRIPT) { value ->
        if (continuation.isActive) continuation.resume(value)
    }
}

private val SEARCH_PAGE_SNAPSHOT_SCRIPT = """
(function() {
    if (!document.body || location.origin !== ${Json.encodeToString(LinovelibConstants.BASE_URL)}) return null;
    var blocked = !!document.querySelector(
        '#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]'
    ) || /Just a moment|Attention Required|安全验证|安全驗證/.test(document.title);
    if (blocked) return {url: location.href, blocked: true};
    if (document.readyState === 'complete' && typeof document.body.innerText === 'string' &&
        !document.body.innerText.trim() && !document.querySelector('form, img, svg, canvas, iframe, video')) {
        return {url: location.href, blank: true};
    }
    var input = document.querySelector('form input[name="searchkey"]');
    var formReady = !!input && document.readyState === 'complete';
    var knownSearchPath = /^(?:\/search\.(?:html|php)|\/modules\/article\/search\.php|\/S\d+\/?|\/search\/[^/]+_\d+\.html)${'$'}/.test(location.pathname);
    var hasResultSummary = /共\s*(?:搜索|搜尋)\s*到\s*\d+\s*[部本]\s*[与與]\s*[“"'「『].*?[”"'」』]\s*(?:相关|相關)\s*(?:结果|結果)/.test(document.body.innerText || '');
    if (!knownSearchPath && !hasResultSummary) {
        return {url: location.href, formReady: formReady, blocked: false};
    }
    var page = document.documentElement.cloneNode(true);
    page.querySelectorAll('script, style, form, input, textarea, button, iframe, header, footer, nav, meta[name="lnr-search-keyword"]').forEach(function(node) {
        node.remove();
    });
    if (input && input.value.trim()) {
        var keyword = document.createElement('meta');
        keyword.name = 'lnr-search-keyword';
        keyword.content = input.value.trim();
        page.querySelector('head').appendChild(keyword);
    }
    return {url: location.href, html: page.outerHTML, formReady: formReady, blocked: false};
})()
"""
