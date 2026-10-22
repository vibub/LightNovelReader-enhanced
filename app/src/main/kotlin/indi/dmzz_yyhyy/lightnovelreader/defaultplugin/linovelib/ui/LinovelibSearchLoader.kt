package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalResources
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

@Composable
fun LinovelibSearchLoader(
    request: LinovelibWebSearchRequest?,
    showBrowser: Boolean,
    onShowBrowser: () -> Unit,
    onPageReady: suspend (requestId: Long, url: String, html: String) -> Boolean,
    onCancel: () -> Unit,
    onSearchStateChanged: (requestId: Long, errorMessage: String?) -> Unit,
    onBookDetected: (requestId: Long, bookId: String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var browserStarted by remember { mutableStateOf(request != null) }
    var navigationStarted by remember { mutableStateOf(false) }
    var initializeSearch by remember { mutableStateOf(true) }
    var formSubmitted by remember(request?.id) { mutableStateOf(false) }
    var targetPageIssued by remember(request?.id) { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var lastBookUrl by remember { mutableStateOf<String?>(null) }
    var searchAttempt by remember(request?.id) { mutableIntStateOf(0) }
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var httpError by remember { mutableStateOf<String?>(null) }
    var verificationPaused by remember { mutableStateOf(false) }
    var showVerificationDialog by remember(request?.id) { mutableStateOf(false) }
    var verificationApproved by remember(request?.id) { mutableStateOf(false) }
    val currentResources by rememberUpdatedState(LocalResources.current)
    val currentRequest by rememberUpdatedState(request)
    val currentShowBrowser by rememberUpdatedState(showBrowser)
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnShowBrowser by rememberUpdatedState(onShowBrowser)
    val currentOnSearchStateChanged by rememberUpdatedState(onSearchStateChanged)
    val currentOnBookDetected by rememberUpdatedState(onBookDetected)
    val lifecycleOwner = LocalLifecycleOwner.current
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }
    val showLoadError: (String) -> Unit = { message ->
        pageLoading = false
        pageError = message
    }
    val retrySearch: () -> Unit = {
        if (!pageLoading && currentRequest != null) searchAttempt++
    }

    LaunchedEffect(request?.id) {
        if (request != null) browserStarted = true
    }
    LaunchedEffect(webView, request?.id, searchAttempt) {
        val view = webView ?: return@LaunchedEffect
        val pending = request ?: return@LaunchedEffect
        val firstNavigation = !navigationStarted
        initializeSearch = firstNavigation || pending.page == 1 || pageError != null || searchAttempt > 0
        navigationStarted = true
        formSubmitted = false
        targetPageIssued = false
        pageError = null
        httpError = null
        verificationPaused = false
        pageLoading = true
        if (!firstNavigation) {
            view.stopLoading()
            if (initializeSearch) {
                view.loadUrl(LinovelibConstants.BASE_URL)
            } else {
                targetPageIssued = true
                view.navigateToSearchPage(pending)
            }
        }
    }

    BackHandler(enabled = showBrowser && request != null) {
        if (webView?.canGoBack() == true) webView?.goBack() else onCancel()
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
    LaunchedEffect(pageError, request?.id) {
        request?.let { currentOnSearchStateChanged(it.id, pageError) }
    }
    LaunchedEffect(webView, showBrowser) {
        webView?.apply {
            visibility = View.VISIBLE
            isFocusable = showBrowser
            isFocusableInTouchMode = showBrowser
            importantForAccessibility = if (showBrowser) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            if (showBrowser) {
                requestLayout()
                invalidate()
            } else clearFocus()
        }
    }
    LaunchedEffect(webView, request?.id, searchAttempt, verificationPaused, showVerificationDialog) {
        val view = webView ?: return@LaunchedEffect
        if (request == null || verificationPaused || showVerificationDialog) return@LaunchedEffect
        delay(45.seconds)
        if (pageError == null) {
            view.stopLoading()
            showLoadError(currentResources.getString(R.string.linovelib_search_load_error, -8))
        }
    }
    LaunchedEffect(webView, lifecycleOwner, request?.id, searchAttempt) {
        val view = webView ?: return@LaunchedEffect
        val pending = request ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSnapshot: String? = null
            var lastSnapshotLoading = true
            var blankSnapshots = 0
            while (isActive) {
                if (pageError != null || showVerificationDialog) {
                    delay(1.seconds)
                    continue
                }
                val snapshot = withTimeoutOrNull(3.seconds) { view.evaluateSearchScript(SEARCH_PAGE_SNAPSHOT_SCRIPT) }
                if (currentRequest?.id != pending.id) return@repeatOnLifecycle
                val page = snapshot?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                if (!pageLoading && page?.get("blank")?.jsonPrimitive?.booleanOrNull == true) {
                    blankSnapshots++
                    if (blankSnapshots >= 2) showLoadError(httpError ?: currentResources.getString(R.string.linovelib_search_blank_page))
                } else blankSnapshots = 0
                if (snapshot != null && pageError == null &&
                    (snapshot != lastSnapshot || pageLoading != lastSnapshotLoading || httpError != null)
                ) {
                    lastSnapshot = snapshot
                    lastSnapshotLoading = pageLoading
                    if (page?.get("blocked")?.jsonPrimitive?.booleanOrNull == true) {
                        if (!verificationPaused) {
                            verificationPaused = true
                            LinovelibRateLimiter.coolDown("Web search verification required", 60_000, blocked = true)
                        }
                        pageLoading = false
                        httpError = null
                        if (currentShowBrowser || verificationApproved) {
                            currentOnShowBrowser()
                        } else {
                            showVerificationDialog = true
                        }
                    } else if (httpError != null && !pageLoading) {
                        showLoadError(httpError!!)
                    } else if (!pageLoading && initializeSearch && !formSubmitted &&
                        page?.get("home")?.jsonPrimitive?.booleanOrNull == true
                    ) {
                        formSubmitted = true
                        pageLoading = true
                        if (view.evaluateSearchScript(searchFormScript(pending.keyword)) != "true") {
                            showLoadError(currentResources.getString(R.string.linovelib_search_load_error, -1))
                        }
                    } else if (!pageLoading && initializeSearch && formSubmitted &&
                        pending.page > 1 && !targetPageIssued &&
                        page?.get("initialResult")?.jsonPrimitive?.booleanOrNull == true &&
                        page["keyword"]?.jsonPrimitive?.contentOrNull == pending.keyword
                    ) {
                        targetPageIssued = true
                        pageLoading = true
                        view.navigateToSearchPage(pending)
                    } else {
                        val url = page?.get("url")?.jsonPrimitive?.contentOrNull
                        val html = page?.get("html")?.jsonPrimitive?.contentOrNull
                        if (!pageLoading && url != null && html != null &&
                            currentOnPageReady(pending.id, url, html)
                        ) return@repeatOnLifecycle
                    }
                }
                delay(1.seconds)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
            .zIndex(if (showBrowser && request != null) 1f else -1f)
            .graphicsLayer { alpha = if (showBrowser && request != null) 1f else 0f },
        topBar = {
            if (showBrowser && request != null) {
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
                            reloadEnabled = !pageLoading
                        )
                    }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (showBrowser && request != null) {
                Text(
                    text = stringResource(R.string.linovelib_search_verification_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box(Modifier.weight(1f)) {
                if (browserStarted) LinovelibWebView(
                    modifier = Modifier.fillMaxSize(),
                    initialUrl = LinovelibConstants.BASE_URL,
                    mode = LinovelibWebViewMode.DesktopBrowsing,
                    onWebViewCreated = { view -> webView = view },
                    onUrlChanged = { url ->
                        if (url != lastBookUrl) {
                            lastBookUrl = url
                            currentRequest?.let { pending ->
                                LinovelibConstants.extractBookIdFromUrl(url)?.let {
                                    currentOnBookDetected(pending.id, it)
                                }
                            }
                        }
                    },
                    onPageStarted = { _, _ ->
                        pageLoading = true
                        pageError = null
                        httpError = null
                        verificationPaused = false
                    },
                    onPageFinished = { view, _ ->
                        pageLoading = false
                        updateNavigationState(view)
                    },
                    onPageError = { code, retryAfter ->
                        if (currentRequest != null) {
                            val message = currentResources.getString(R.string.linovelib_search_load_error, code)
                            if (code in listOf(403, 429, 503)) {
                                val requestedDelay = LinovelibJsoup.parseRetryAfterMillis(retryAfter)
                                val pause = if (code == 429) requestedDelay ?: 60_000 else maxOf(requestedDelay ?: 0, 60_000)
                                LinovelibRateLimiter.coolDown("Web search HTTP $code", pause, blocked = code != 429)
                            }
                            if (code > 0) httpError = message else showLoadError(message)
                        }
                    }
                )
                if (showBrowser && request != null && pageLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (showVerificationDialog && request != null) {
        LinovelibVerificationDialog(
            onConfirm = {
                verificationApproved = true
                showVerificationDialog = false
                // 直接展示当前验证文档，不重新导航或重放搜索表单。
                currentOnShowBrowser()
            },
            onDismiss = {
                showVerificationDialog = false
                webView?.stopLoading()
                onCancel()
            }
        )
    }
}

private suspend fun WebView.evaluateSearchScript(script: String): String = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { value ->
        if (continuation.isActive) continuation.resume(value)
    }
}

private fun WebView.navigateToSearchPage(request: LinovelibWebSearchRequest) {
    val target = LinovelibConstants.desktopSearchRequestUrl(request.keyword, request.page)
    evaluateJavascript("""
        (function() {
            if (location.origin === ${Json.encodeToString(LinovelibConstants.BASE_URL)}) {
                location.assign(${Json.encodeToString(target)});
            }
        })()
    """.trimIndent(), null)
}

@Suppress("SpellCheckingInspection") // 官网表单字段名，不能按拼写检查改名。
private fun searchFormScript(keyword: String): String = """
(function() {
    if (location.origin !== ${Json.encodeToString(LinovelibConstants.BASE_URL)} || location.pathname !== '/') return false;
    var form = document.querySelector('form[name="t_frmsearch"]');
    var input = form && form.querySelector('input[name="searchkey"]');
    if (!input || form.method.toLowerCase() !== 'post' ||
        form.action !== ${Json.encodeToString("${LinovelibConstants.BASE_URL}/S6/")}) return false;
    input.value = ${Json.encodeToString(keyword)};
    input.dispatchEvent(new Event('input', {bubbles: true}));
    form.requestSubmit();
    return true;
})()
"""

@Suppress("SpellCheckingInspection") // 选择器需保留官网表单字段名。
private val SEARCH_PAGE_SNAPSHOT_SCRIPT = $$"""
(function() {
    if (!document.body || location.origin !== $${Json.encodeToString(LinovelibConstants.BASE_URL)}) return null;
    var blocked = !!document.querySelector(
        '#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]'
    ) || /Just a moment|Attention Required|安全验证|安全驗證/.test(document.title);
    var challengePending = !!document.querySelector('.main-wrapper .main-content') &&
        typeof window._cf_chl_opt === 'object' &&
        !!document.querySelector('script[src*="/cdn-cgi/challenge-platform/"]') &&
        !document.querySelector('.search-tips');
    if (blocked || challengePending) return {url: location.href, blocked: true};
    if (document.readyState === 'complete' && typeof document.body.innerText === 'string' &&
        !document.body.innerText.trim() && !document.querySelector('form, img, svg, canvas, iframe, video')) {
        return {url: location.href, blank: true};
    }
    if (location.pathname === '/' && document.readyState === 'complete') {
        return {url: location.href, home: !!document.querySelector('form[name="t_frmsearch"] input[name="searchkey"]')};
    }
    var knownSearchPath = /^\/S6\/(?:[^/]+_\d+\.html)?$/.test(location.pathname);
    if (!knownSearchPath) return null;
    var page = document.documentElement.cloneNode(true);
    page.querySelectorAll('script, style, form, input, textarea, button, iframe, header, footer, nav, meta[name="lnr-search-keyword"]').forEach(function(node) {
        node.remove();
    });
    return {
        url: location.href, html: page.outerHTML, blocked: false,
        initialResult: /^\/S6\/?$/.test(location.pathname) && !location.search,
        keyword: (document.querySelector('#queryword') || {}).textContent
    };
})()
"""
