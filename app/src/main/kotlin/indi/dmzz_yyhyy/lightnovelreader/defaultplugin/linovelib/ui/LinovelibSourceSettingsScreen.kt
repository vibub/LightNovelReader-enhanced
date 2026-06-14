package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibSourceSettingsScreen(
    uiState: LinovelibSourceSettingsUiState,
    onClickBack: () -> Unit,
    onSaveCookie: (String) -> Unit,
    onClearCookie: () -> Unit,
    onSyncNow: () -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var lastLoadedUrl by remember { mutableStateOf(LinovelibConstants.loginUrl()) }
    var menuExpanded by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_settings_title)) },
                navigationIcon = {
                    OutlinedButton(onClick = onClickBack) {
                        Text(stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px),
                            contentDescription = stringResource(R.string.linovelib_refresh)
                        )
                    }
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
                                if (!lastLoadedUrl.contains("bookcase.php")) {
                                    webView?.loadUrl(LinovelibConstants.bookcaseUrl())
                                }
                                onSyncNow()
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
            onWebViewCreated = { webView = it },
            onUrlChanged = { lastLoadedUrl = it }
        )
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
    onBookDetected: (String) -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var detectedBookId by remember { mutableStateOf<String?>(null) }
    val searchUrl = remember { LinovelibConstants.MOBILE_BASE_URL }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_web_search_title)) },
                navigationIcon = {
                    OutlinedButton(onClick = onClickBack) {
                        Text(stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) {
                        Icon(
                            painter = painterResource(R.drawable.refresh_24px),
                            contentDescription = stringResource(R.string.linovelib_refresh)
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
            initialUrl = searchUrl,
            onWebViewCreated = { webView = it },
            onUrlChanged = { url ->
                val bookId = LinovelibConstants.extractBookIdFromUrl(url)
                if (bookId != null && detectedBookId != bookId) {
                    detectedBookId = bookId
                    onBookDetected(bookId)
                }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LinovelibWebView(
    modifier: Modifier,
    initialUrl: String,
    onWebViewCreated: (WebView) -> Unit = {},
    onUrlChanged: (String) -> Unit = {}
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString = settings.userAgentString
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        CookieManager.getInstance().flush()
                        onUrlChanged(url)
                    }
                }
                onWebViewCreated(this)
                loadUrl(initialUrl)
            }
        },
        update = {}
    )
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
