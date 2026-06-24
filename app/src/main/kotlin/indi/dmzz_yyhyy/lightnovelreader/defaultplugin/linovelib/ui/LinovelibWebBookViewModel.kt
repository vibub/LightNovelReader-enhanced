package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import javax.inject.Inject

@HiltViewModel
class LinovelibWebBookViewModel @Inject constructor(
    userDataRepository: UserDataRepository
) : ViewModel() {
    private val accountStore = LinovelibAccountStore(userDataRepository)

    fun getCookie(): String = accountStore.getCookie()
}
