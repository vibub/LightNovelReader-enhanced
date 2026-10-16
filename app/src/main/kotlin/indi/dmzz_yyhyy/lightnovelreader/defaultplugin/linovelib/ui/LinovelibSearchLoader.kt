package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibWebSearchRequest
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
    onPageReady: suspend (url: String, html: String) -> Boolean,
    onCancel: () -> Unit,
    onBookDetected: (String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var showBrowser by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var lastBookUrl by remember { mutableStateOf<String?>(null) }
    var searchSubmitted by remember { mutableStateOf(false) }
    val currentOnPageReady by rememberUpdatedState(onPageReady)
    val currentOnBookDetected by rememberUpdatedState(onBookDetected)
    val lifecycleOwner = LocalLifecycleOwner.current
    val initialUrl = LinovelibConstants.FILTER_BASE_URL
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    BackHandler {
        if (showBrowser && webView?.canGoBack() == true) {
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
            isFocusable = showBrowser
            isFocusableInTouchMode = showBrowser
            if (!showBrowser) clearFocus()
        }
    }
    LaunchedEffect(request.id) {
        delay(12_000)
        showBrowser = true
    }
    LaunchedEffect(webView, lifecycleOwner) {
        val view = webView ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastSnapshot: String? = null
            while (true) {
                val snapshot = withTimeoutOrNull(3_000) { view.searchPageSnapshot() }
                if (snapshot != null && snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    val page = runCatching { Json.parseToJsonElement(snapshot).jsonObject }.getOrNull()
                    if (page?.get("blocked")?.jsonPrimitive?.booleanOrNull == true) {
                        showBrowser = true
                    } else if (!searchSubmitted && page?.get("formReady")?.jsonPrimitive?.booleanOrNull == true) {
                        view.submitLinovelibSearch(request.keyword) { submitted ->
                            searchSubmitted = submitted
                            if (!submitted) showBrowser = true
                        }
                    } else {
                        val url = page?.get("url")?.jsonPrimitive?.contentOrNull
                        val html = page?.get("html")?.jsonPrimitive?.contentOrNull
                        if (url != null && html != null && currentOnPageReady(url, html)) return@repeatOnLifecycle
                    }
                }
                delay(1_000)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_web_search_title)) },
                navigationIcon = { LinovelibWebCloseButton(onCancel) },
                actions = {
                    if (showBrowser) {
                        LinovelibWebNavigationActions(
                            webView = webView,
                            canGoBack = canGoBack,
                            canGoForward = canGoForward,
                            updateNavigationState = updateNavigationState
                        )
                    }
                }
            )
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
                    initialUrl = initialUrl,
                    onWebViewCreated = { webView = it },
                    onUrlChanged = { url ->
                        if (url != lastBookUrl) {
                            lastBookUrl = url
                            LinovelibConstants.extractBookIdFromUrl(url)?.let(currentOnBookDetected)
                        }
                    },
                    onPageFinished = { view, _ ->
                        updateNavigationState(view)
                        if (!searchSubmitted) {
                            view.submitLinovelibSearch(request.keyword) { submitted ->
                                if (submitted) searchSubmitted = true
                            }
                        }
                    }
                )
                if (!showBrowser) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.linovelib_search_loading))
                            TextButton(onClick = { showBrowser = true }) {
                                Text(stringResource(R.string.linovelib_search_open_web))
                            }
                        }
                    }
                }
            }
        }
    }
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

private const val SEARCH_PAGE_SNAPSHOT_SCRIPT = """
(function() {
    if (!document.body) return null;
    var blocked = !!document.querySelector(
        '#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]'
    ) || /Just a moment|Attention Required|安全验证|安全驗證/.test(document.title);
    if (blocked) return {url: location.href, blocked: true};
    if (!/\/search\.(html|php)$/.test(location.pathname)) return null;
    var input = document.querySelector('form input[name="searchkey"]');
    var formReady = !!input && input.form.method.toLowerCase() === 'post' && document.readyState === 'complete';
    var page = document.documentElement.cloneNode(true);
    page.querySelectorAll('script, style, form, input, textarea, button, iframe, header, footer, nav').forEach(function(node) {
        node.remove();
    });
    return {url: location.href, html: page.outerHTML, formReady: formReady, blocked: false};
})()
"""
