package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import android.os.Bundle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LinovelibWebSearchViewModel @Inject constructor() : ViewModel() {
    private var webViewState: Bundle? = null
    private var lastObservedUrl: String? = null

    fun getWebViewState(): Bundle? = webViewState?.let(::Bundle)

    fun saveWebViewState(state: Bundle?) {
        webViewState = state?.let(::Bundle)
    }

    fun hasUrlChanged(url: String): Boolean {
        if (url == lastObservedUrl) return false
        lastObservedUrl = url
        return true
    }
}
