package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibBookmarkRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync.LinovelibSyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LinovelibWebBookViewModel @Inject constructor(
    userDataRepository: UserDataRepository,
    private val syncRepository: LinovelibSyncRepository,
    private val bookmarkRepository: LinovelibBookmarkRepository
) : ViewModel() {
    private val accountStore = LinovelibAccountStore(userDataRepository)

    fun getCookie(): String = accountStore.getCookie()

    suspend fun verifyBookmarkSynced(bookId: String, chapterId: String): Boolean = withContext(Dispatchers.IO) {
        val localTitle = bookmarkRepository.getBookmark(bookId)?.chapterTitle.orEmpty()
        val synced = syncRepository.isRemoteBookmarkAt(bookId, chapterId, localTitle)
        if (synced) {
            syncRepository.syncRemoteToLocal()
            bookmarkRepository.markSynced(bookId)
        } else {
            bookmarkRepository.markFailed(bookId)
        }
        synced
    }

    fun markBookmarkSyncFailed(bookId: String) {
        bookmarkRepository.markFailed(bookId)
    }
}
