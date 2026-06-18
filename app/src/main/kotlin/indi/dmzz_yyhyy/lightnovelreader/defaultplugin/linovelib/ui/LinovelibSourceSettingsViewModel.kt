package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

@HiltViewModel
class LinovelibSourceSettingsViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    private val syncRepository: LinovelibSyncRepository,
    webBookDataSourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val canSync = webBookDataSourceProvider.default.id == LinovelibConstants.SOURCE_ID
    private val _uiState = MutableStateFlow(LinovelibSourceSettingsUiState(canSync = canSync))
    val uiState: StateFlow<LinovelibSourceSettingsUiState> = _uiState

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                accountStore.hasCookieFlow(),
                accountStore.getLastSyncTimeFlow(),
                accountStore.getLastSyncSummaryFlow(),
                accountStore.getLastSyncErrorFlow()
            ) { hasCookie, lastSyncTime, lastSyncSummary, lastSyncError ->
                LinovelibSourceSettingsUiState(
                    hasCookie = hasCookie,
                    lastSyncTime = lastSyncTime.formatLastSyncTime(),
                    lastSyncSummary = lastSyncSummary,
                    lastSyncError = lastSyncError,
                    canSync = canSync,
                    isSyncing = _uiState.value.isSyncing
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun saveCookie(cookie: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (cookie.isBlank()) {
                accountStore.markSyncError("未读取到 Linovelib Cookie，请先在 WebView 中完成登录")
                return@launch
            }
            accountStore.saveCookie(cookie)
        }
    }

    fun clearSavedCookie() {
        viewModelScope.launch(Dispatchers.IO) {
            accountStore.clearCookie()
        }
    }

    fun syncNow() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true) }
            try {
                syncRepository.syncRemoteToLocal()
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }
}

private val lastSyncTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun String.formatLastSyncTime(): String {
    if (isBlank()) return ""
    return try {
        Instant.parse(this)
            .atZone(ZoneId.systemDefault())
            .format(lastSyncTimeFormatter)
    } catch (_: DateTimeParseException) {
        runCatching { LocalDateTime.parse(this).format(lastSyncTimeFormatter) }
            .getOrElse { this }
    }
}

data class LinovelibSourceSettingsUiState(
    val hasCookie: Boolean = false,
    val lastSyncTime: String = "",
    val lastSyncSummary: String = "",
    val lastSyncError: String = "",
    val canSync: Boolean = false,
    val isSyncing: Boolean = false
)
