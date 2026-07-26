package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibWebBookScreen(
    bookId: String,
    chapterId: String = "",
    onClickBack: () -> Unit,
    viewModel: LinovelibWebBookViewModel = hiltViewModel()
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
            updateNavigationState = updateNavigationState
        )
    }

    val initialUrl = remember(bookId, chapterId) {
        if (chapterId.isNotBlank()) {
            LinovelibConstants.mobileChapterUrl(bookId, chapterId)
        } else {
            LinovelibConstants.mobileDetailUrl(bookId)
        }
    }
    val initialCookies by viewModel.cookie.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            webView = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linovelib_web_book_title)) },
                navigationIcon = {
                    LinovelibWebCloseButton(onClickBack)
                },
                actions = {
                    LinovelibWebNavigationActions(
                        webView = webView,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        updateNavigationState = updateNavigationState
                    )
                }
            )
        }
    ) { innerPadding ->
        val cookie = initialCookies
        if (cookie == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LinovelibWebView(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                initialUrl = initialUrl,
                initialCookies = cookie,
                onWebViewCreated = {
                    webView = it
                    updateNavigationState(it)
                },
                onPageFinished = { view, _ ->
                    updateNavigationState(view)
                }
            )
        }
    }
}
