package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibWebBookScreen(
    bookId: String,
    chapterId: String = "",
    autoBookmark: Boolean = false,
    onClickBack: () -> Unit,
    viewModel: LinovelibWebBookViewModel = hiltViewModel()
) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var autoBookmarkTriggered by remember(bookId, chapterId, autoBookmark) { mutableStateOf(false) }
    var autoBookmarkMessage by remember { mutableStateOf("") }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    val updateNavigationState: (WebView?) -> Unit = { view ->
        canGoBack = view?.canGoBack() == true
        canGoForward = view?.canGoForward() == true
    }
    val coroutineScope = rememberCoroutineScope()

    val initialUrl = remember(bookId, chapterId) {
        if (chapterId.isNotBlank()) {
            LinovelibConstants.mobileChapterUrl(bookId, chapterId)
        } else {
            LinovelibConstants.mobileDetailUrl(bookId)
        }
    }

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
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (autoBookmarkMessage.isNotBlank()) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    text = autoBookmarkMessage
                )
            }
            LinovelibWebView(
                modifier = Modifier.fillMaxSize(),
                initialUrl = initialUrl,
                initialCookies = viewModel.getCookie(),
                onWebViewCreated = {
                    webView = it
                    updateNavigationState(it)
                },
                onPageFinished = { view, _ ->
                    updateNavigationState(view)
                    if (!autoBookmark || chapterId.isBlank() || autoBookmarkTriggered) return@LinovelibWebView
                    autoBookmarkTriggered = true
                    autoBookmarkMessage = "正在同步章节书签…"
                    view.postDelayed({
                        view.evaluateJavascript(AUTO_BOOKMARK_SCRIPT) { result ->
                            if (result.contains("clicked")) {
                                coroutineScope.launch {
                                    delay(1500.milliseconds)
                                    val synced = viewModel.verifyBookmarkSynced(bookId, chapterId)
                                    autoBookmarkMessage = if (synced) {
                                        "章节书签已同步到 Bilinovel"
                                    } else {
                                        "已尝试同步，请在网页或稍后同步书架确认"
                                    }
                                }
                            } else {
                                autoBookmarkMessage = "未找到网页书签按钮，请在网页中手动点击星星"
                            }
                        }
                    }, 1000L)
                }
            )
        }
    }
}

private const val AUTO_BOOKMARK_SCRIPT = """
(function() {
  const nodes = Array.from(document.querySelectorAll('a,button,[onclick],[role="button"],.star,.bookmark,[class*="bookmark"],[title*="书签"],[aria-label*="书签"]'));
  function textOf(el) {
    return [el.innerText, el.title, el.getAttribute('aria-label'), el.className, el.getAttribute('href'), el.getAttribute('onclick')]
      .filter(Boolean).join(' ');
  }
  function rejected(text) {
    return /取消|删除|移除|remove|delete|加入书架|收藏本书|书架|bookcase|bookshelf/i.test(text);
  }
  const explicit = nodes.find(function(el) {
    const text = textOf(el);
    return /书签|标记本章|bookmark/i.test(text) && !rejected(text);
  });
  const star = nodes.find(function(el) {
    const text = textOf(el);
    return /star|icon-star/i.test(text) && !rejected(text);
  });
  const target = explicit || star;
  if (!target) return 'not_found';
  target.click();
  return 'clicked';
})();
"""
