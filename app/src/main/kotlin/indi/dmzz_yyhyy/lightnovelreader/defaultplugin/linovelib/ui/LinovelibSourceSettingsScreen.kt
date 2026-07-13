package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import java.net.URLEncoder

private class WebViewHolder {
    var webView: WebView? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibSourceSettingsScreen(
    uiState: LinovelibSourceSettingsUiState,
    onClickBack: () -> Unit,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onSyncNow: () -> Unit
) {
    val webViewHolder = remember { WebViewHolder() }
    var lastLoadedUrl by remember { mutableStateOf(LinovelibConstants.loginUrl()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingSyncBookcase by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    BackHandler {
        handleLinovelibWebViewBack(
            webView = webViewHolder.webView,
            onNoHistory = onClickBack,
            updateNavigationState = updateNavigationState
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewHolder.webView?.run {
                stopLoading()
                destroy()
            }
            webViewHolder.webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_settings_title)) },
                navigationIcon = {
                    LinovelibWebCloseButton(onClickBack)
                },
                actions = {
                    LinovelibWebNavigationActions(
                        webView = webViewHolder.webView,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        updateNavigationState = updateNavigationState
                    )
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            painter = painterResource(R.drawable.more_vert_24px),
                            contentDescription = stringResource(R.string.linovelib_account_menu)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        LinovelibAccountMenuContent(
                            uiState = uiState,
                            currentUrl = lastLoadedUrl,
                            onSaveCookie = {
                                CookieManager.getInstance().flush()
                                onSaveCookie(CookieManager.getInstance().collectLinovelibCookie(lastLoadedUrl))
                            },
                            onClearCookie = {
                                onClearCookie()
                            },
                            onSyncBookcase = {
                                CookieManager.getInstance().flush()
                                if (lastLoadedUrl.contains("bookcase.php")) {
                                    onSyncNow()
                                } else {
                                    pendingSyncBookcase = true
                                    webViewHolder.webView?.loadUrl(LinovelibConstants.bookcaseUrl())
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LinovelibWebView(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            initialUrl = LinovelibConstants.loginUrl(),
            mode = LinovelibWebViewMode.Login,
            onWebViewCreated = {
                webViewHolder.webView = it
                updateNavigationState(it)
            },
            onUrlChanged = { lastLoadedUrl = it },
            onPageFinished = { view, url ->
                updateNavigationState(view)
                if (pendingSyncBookcase && url.contains("bookcase.php")) {
                    pendingSyncBookcase = false
                    onSyncNow()
                }
            }
        )
    }
}

@Composable
fun LinovelibWebCloseButton(onClickBack: () -> Unit) {
    IconButton(onClick = onClickBack) {
        Icon(
            painter = painterResource(R.drawable.close_24px),
            contentDescription = stringResource(R.string.close)
        )
    }
}

@Composable
fun LinovelibWebNavigationActions(
    webView: WebView?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    updateNavigationState: (WebView?) -> Unit,
    onBeforeGoBack: () -> Unit = {},
    onBeforeGoForward: () -> Unit = {}
) {
    IconButton(
        enabled = canGoBack,
        onClick = {
            webView?.run {
                if (this.canGoBack()) {
                    onBeforeGoBack()
                    goBack()
                }
                updateNavigationState(this)
            }
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.arrow_back_24px),
            contentDescription = stringResource(R.string.linovelib_web_back)
        )
    }
    IconButton(
        enabled = canGoForward,
        onClick = {
            webView?.run {
                if (this.canGoForward()) {
                    onBeforeGoForward()
                    goForward()
                }
                updateNavigationState(this)
            }
        }
    ) {
        Icon(
            painter = painterResource(R.drawable.arrow_forward_24px),
            contentDescription = stringResource(R.string.linovelib_web_forward)
        )
    }
    IconButton(onClick = { webView?.reload() }) {
        Icon(
            painter = painterResource(R.drawable.refresh_24px),
            contentDescription = stringResource(R.string.linovelib_refresh)
        )
    }
}

internal fun handleLinovelibWebViewBack(
    webView: WebView?,
    onNoHistory: () -> Unit,
    updateNavigationState: (WebView?) -> Unit,
    onBeforeGoBack: () -> Unit = {}
) {
    if (webView?.canGoBack() == true) {
        onBeforeGoBack()
        webView.goBack()
        updateNavigationState(webView)
    } else {
        onNoHistory()
    }
}

@Composable
private fun LinovelibAccountMenuContent(
    uiState: LinovelibSourceSettingsUiState,
    currentUrl: String,
    onSaveCookie: () -> Unit,
    onClearCookie: () -> Unit,
    onSyncBookcase: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 280.dp, max = 360.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(
                if (uiState.hasCookie) R.string.linovelib_login_status_saved
                else R.string.linovelib_login_status_missing
            ),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.linovelib_cookie_storage_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (uiState.lastSyncTime.isNotBlank()) {
            Text(
                text = stringResource(R.string.linovelib_last_sync_time, uiState.lastSyncTime),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (uiState.lastSyncSummary.isNotBlank()) {
            Text(
                text = uiState.lastSyncSummary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (uiState.lastSyncError.isNotBlank()) {
            Text(
                text = stringResource(R.string.linovelib_last_sync_error, uiState.lastSyncError),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            text = stringResource(R.string.linovelib_webview_hint, currentUrl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSaveCookie
        ) {
            Text(stringResource(R.string.linovelib_save_cookie))
        }
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClearCookie
        ) {
            Text(stringResource(R.string.linovelib_clear_cookie))
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onSyncBookcase,
            enabled = uiState.hasCookie && uiState.canSync && !uiState.isSyncing
        ) {
            if (uiState.isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            Text(stringResource(R.string.linovelib_sync_bookcase))
        }
        if (!uiState.canSync) {
            Text(
                text = stringResource(R.string.linovelib_sync_requires_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibWebSearchScreen(
    keyword: String,
    onClickBack: () -> Unit,
    onBookDetected: (String) -> Unit,
    viewModel: LinovelibWebSearchViewModel = hiltViewModel()
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }

    BackHandler {
        handleLinovelibWebViewBack(
            webView = webView,
            onNoHistory = onClickBack,
            updateNavigationState = updateNavigationState,
            onBeforeGoBack = viewModel::beginBackNavigation
        )
    }

    val searchUrl = remember(keyword) {
        keyword.trim().takeIf { it.isNotBlank() }
            ?.let { LinovelibConstants.searchUrl(URLEncoder.encode(it, Charsets.UTF_8.name())) }
            ?: LinovelibConstants.MOBILE_BASE_URL
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.finishBackNavigation()
            webView?.run {
                val state = Bundle()
                viewModel.saveWebViewState(state.takeIf { saveState(it) != null })
                stopLoading()
                destroy()
            }
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_web_search_title)) },
                navigationIcon = {
                    LinovelibWebCloseButton(onClickBack)
                },
                actions = {
                    LinovelibWebNavigationActions(
                        webView = webView,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        updateNavigationState = updateNavigationState,
                        onBeforeGoBack = viewModel::beginBackNavigation,
                        onBeforeGoForward = viewModel::finishBackNavigation
                    )
                }
            )
        }
    ) { innerPadding ->
        LinovelibWebView(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            initialUrl = searchUrl,
            restoredState = viewModel.getWebViewState(),
            onWebViewCreated = {
                viewModel.finishBackNavigation()
                webView = it
                updateNavigationState(it)
            },
            onUrlChanged = { url ->
                if (viewModel.shouldDetectBookAtUrl(url)) {
                    LinovelibConstants.extractBookIdFromUrl(url)?.let(onBookDetected)
                }
            },
            onPageFinished = { view, _ ->
                updateNavigationState(view)
                viewModel.finishBackNavigation()
            }
        )
    }
}

private const val LOGIN_INPUT_CARET_FIX_SCRIPT = """
(function() {
  if (window.__lnrLoginCaretFixInstalled) return 'already_installed';
  window.__lnrLoginCaretFixInstalled = true;
  var composing = false;
  var lastValues = new WeakMap();
  var allowedTypes = ['', 'text', 'password', 'email', 'search', 'tel', 'url'];
  function isLoginInput(el) {
    if (!el || el.tagName !== 'INPUT') return false;
    var type = (el.getAttribute('type') || '').toLowerCase();
    return allowedTypes.indexOf(type) >= 0;
  }
  function applyDirection(el) {
    if (!isLoginInput(el)) return;
    try {
      el.style.direction = 'ltr';
      el.style.textAlign = 'left';
      el.setAttribute('dir', 'ltr');
    } catch (e) {}
  }
  function moveToEndIfReset(el) {
    if (composing || !isLoginInput(el) || document.activeElement !== el) return;
    try {
      var end = el.value ? el.value.length : 0;
      if (end > 0 && (el.selectionStart === 0 || el.selectionEnd === 0 || el.selectionStart == null)) {
        el.setSelectionRange(end, end);
      }
    } catch (e) {}
  }
  function scheduleMove(el) {
    if (!isLoginInput(el)) return;
    applyDirection(el);
    if (window.requestAnimationFrame) {
      window.requestAnimationFrame(function() { moveToEndIfReset(el); });
    }
    window.setTimeout(function() { moveToEndIfReset(el); }, 0);
    window.setTimeout(function() { moveToEndIfReset(el); }, 80);
  }
  function scan(root) {
    if (!root || !root.querySelectorAll) return;
    Array.prototype.forEach.call(root.querySelectorAll('input'), applyDirection);
  }
  document.addEventListener('focusin', function(e) { applyDirection(e.target); }, true);
  document.addEventListener('keydown', function(e) {
    if (lastValues.has(e.target)) moveToEndIfReset(e.target);
  }, true);
  document.addEventListener('input', function(e) {
    if (!isLoginInput(e.target)) return;
    lastValues.set(e.target, e.target.value || '');
    scheduleMove(e.target);
  }, true);
  document.addEventListener('keyup', function(e) { scheduleMove(e.target); }, true);
  document.addEventListener('compositionstart', function() { composing = true; }, true);
  document.addEventListener('compositionend', function(e) {
    composing = false;
    scheduleMove(e.target);
  }, true);
  scan(document);
  if (window.MutationObserver && document.documentElement) {
    new MutationObserver(function(mutations) {
      mutations.forEach(function(mutation) {
        Array.prototype.forEach.call(mutation.addedNodes, function(node) {
          if (isLoginInput(node)) applyDirection(node);
          scan(node);
        });
      });
    }).observe(document.documentElement, { childList: true, subtree: true });
  }
  return 'installed';
})();
"""

enum class LinovelibWebViewMode {
    Browsing,
    Login
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinovelibWebView(
    modifier: Modifier,
    initialUrl: String,
    restoredState: Bundle? = null,
    initialCookies: String = "",
    mode: LinovelibWebViewMode = LinovelibWebViewMode.Browsing,
    onWebViewCreated: (WebView) -> Unit = {},
    onUrlChanged: (String) -> Unit = {},
    onPageFinished: (WebView, String) -> Unit = { _, _ -> }
) {
    val currentOnWebViewCreated by rememberUpdatedState(onWebViewCreated)
    val currentOnUrlChanged by rememberUpdatedState(onUrlChanged)
    val currentOnPageFinished by rememberUpdatedState(onPageFinished)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(true)
            if (initialCookies.isNotBlank()) {
                CookieManager.getInstance().applyLinovelibCookie(initialCookies)
            }
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (mode == LinovelibWebViewMode.Browsing) {
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                } else {
                    settings.loadWithOverviewMode = false
                    settings.useWideViewPort = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
                    }
                }
                isFocusable = true
                isFocusableInTouchMode = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        currentOnUrlChanged(url)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        if (mode == LinovelibWebViewMode.Login) {
                            view.evaluateJavascript(LOGIN_INPUT_CARET_FIX_SCRIPT, null)
                        }
                        currentOnUrlChanged(url)
                        currentOnPageFinished(view, url)
                    }
                }
                val restored = restoredState?.let { restoreState(it) } != null
                currentOnWebViewCreated(this)
                if (!restored) {
                    loadUrl(initialUrl)
                }
            }
        },
        update = {}
    )
}

private fun CookieManager.applyLinovelibCookie(cookie: String) {
    cookie.split(';')
        .map { it.trim() }
        .filter { it.isNotBlank() && '=' in it }
        .forEach { value ->
            setCookie(LinovelibConstants.MOBILE_BASE_URL, value)
            setCookie(LinovelibConstants.BASE_URL, value)
        }
    flush()
}

private fun CookieManager.collectLinovelibCookie(currentUrl: String): String {
    val cookieMap = linkedMapOf<String, String>()
    listOf(
        currentUrl,
        LinovelibConstants.loginUrl(),
        LinovelibConstants.MOBILE_BASE_URL,
        LinovelibConstants.BASE_URL
    ).distinct()
        .mapNotNull { url -> runCatching { getCookie(url) }.getOrNull() }
        .flatMap { it.split(';') }
        .map { it.trim() }
        .filter { it.isNotBlank() && '=' in it }
        .forEach { cookie ->
            val name = cookie.substringBefore('=').trim()
            if (name.isNotBlank() && name !in cookieMap) {
                cookieMap[name] = cookie
            }
        }
    return cookieMap.values.joinToString("; ")
}
