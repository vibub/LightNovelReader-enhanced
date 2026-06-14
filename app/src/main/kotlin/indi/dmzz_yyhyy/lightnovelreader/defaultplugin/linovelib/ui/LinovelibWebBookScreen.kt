package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinovelibWebBookScreen(
    bookId: String,
    chapterId: String = "",
    onClickBack: () -> Unit
) {
    var webView: WebView? by remember { mutableStateOf(null) }

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
            initialUrl = initialUrl,
            onWebViewCreated = { webView = it }
        )
    }
}
