package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinovelibWebBookViewModel @Inject constructor(
    userDataRepository: UserDataRepository
) : ViewModel() {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val mutableCookie = MutableStateFlow<String?>(null)
    val cookie: StateFlow<String?> = mutableCookie.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            mutableCookie.value = accountStore.refreshCookie()
        }
    }
}
