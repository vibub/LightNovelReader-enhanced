package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account.LinovelibAccountStore
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.LinovelibWebsiteDataSource
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net.LinovelibJsoup
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class LinovelibSyncRepository @Inject constructor(
    private val userDataRepository: UserDataRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    private val localBookDataSource: LocalBookDataSource,
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val jsoup = LinovelibJsoup(accountStore)
    private val websiteDataSource = LinovelibWebsiteDataSource(jsoup)
    private val accountDataSource = LinovelibAccountDataSource(jsoup, accountStore)

    suspend fun syncRemoteToLocal(): LinovelibSyncResult = withContext(Dispatchers.IO) {
        if (webBookDataSourceProvider.default.id != LinovelibConstants.SOURCE_ID) {
            val message = "请先切换到 Linovelib 数据源后再同步"
            accountStore.markSyncError(message)
            return@withContext LinovelibSyncResult(error = message)
        }
        if (!accountStore.hasCookie()) {
            val message = "尚未保存 Linovelib 登录 Cookie"
            accountStore.markSyncError(message)
            return@withContext LinovelibSyncResult(error = message)
        }
        runCatching {
            ensureSyncBookshelf()
            val remoteBooks = accountDataSource.getRemoteBookshelf()
            var addedOrUpdatedBooks = 0
            var readingProgressUpdated = 0
            val failedBookIds = mutableListOf<String>()
            remoteBooks.forEach { remoteBook ->
                runCatching {
                    val bookInformation = websiteDataSource.getBookInformation(remoteBook.bookId)
                    if (bookInformation.isEmpty()) error("书籍详情为空")
                    localBookDataSource.updateBookInformation(bookInformation)
                    bookshelfRepository.addBookIntoBookShelf(LinovelibConstants.SYNC_BOOKSHELF_ID, bookInformation)
                    addedOrUpdatedBooks++
                    if (remoteBook.lastReadChapterId.isNotBlank()) {
                        val volumes = websiteDataSource.getBookVolumes(remoteBook.bookId)
                        if (!volumes.isEmpty()) {
                            localBookDataSource.updateBookVolumes(volumes)
                            if (updateReadingProgress(remoteBook, volumes)) readingProgressUpdated++
                        }
                    }
                }.onFailure {
                    it.printStackTrace()
                    failedBookIds += remoteBook.bookId
                }
            }
            val now = LocalDateTime.now().toString()
            val summary = "同步完成：书籍 $addedOrUpdatedBooks 本，阅读进度 $readingProgressUpdated 本，失败 ${failedBookIds.size} 本"
            accountStore.markSyncSuccess(now, summary)
            LinovelibSyncResult(
                syncedBooks = addedOrUpdatedBooks,
                syncedReadingProgress = readingProgressUpdated,
                failedBookIds = failedBookIds,
                summary = summary
            )
        }.getOrElse { throwable ->
            throwable.printStackTrace()
            val message = throwable.message ?: throwable.javaClass.simpleName
            accountStore.markSyncError(message)
            LinovelibSyncResult(error = message)
        }
    }

    private fun ensureSyncBookshelf() {
        if (bookshelfRepository.getBookshelf(LinovelibConstants.SYNC_BOOKSHELF_ID) != null) return
        bookshelfRepository.createBookShelf(
            id = LinovelibConstants.SYNC_BOOKSHELF_ID,
            name = LinovelibConstants.SYNC_BOOKSHELF_NAME,
            sortType = BookshelfSortType.Default,
            autoCache = false,
            systemUpdateReminder = false
        )
    }

    private fun updateReadingProgress(
        remoteBook: LinovelibAccountDataSource.LinovelibRemoteBook,
        volumes: BookVolumes
    ): Boolean {
        val chapters = volumes.volumes.flatMap { it.chapters }
        val remoteIndex = chapters.indexOfFirst { it.id == remoteBook.lastReadChapterId }
        if (remoteIndex < 0) return false
        val remoteChapter = chapters[remoteIndex]
        val remoteOverallProgress = ((remoteIndex + remoteBook.progress.coerceIn(0f, 1f)) / chapters.size)
            .coerceIn(0f, 1f)
        var updated = false
        bookRepository.updateUserReadingData(remoteBook.bookId) { local ->
            val localIndex = chapters.indexOfFirst { it.id == local.lastReadChapterId }
            val localChapterProgress = local.currentChapterReadingProgressMap[remoteBook.lastReadChapterId] ?: 0f
            val isRemoteAhead = remoteIndex > localIndex ||
                (remoteIndex == localIndex && remoteBook.progress > localChapterProgress)
            if (!isRemoteAhead) return@updateUserReadingData local
            updated = true
            local.lastReadTime = LocalDateTime.now()
            local.totalReadTime = max(local.totalReadTime, 0)
            local.readingProgress = max(local.readingProgress, remoteOverallProgress)
            local.lastReadChapterId = remoteChapter.id
            local.lastReadChapterTitle = remoteChapter.title
            local.updateChapterReadingProgress(remoteChapter.id, remoteBook.progress.coerceIn(0f, 1f))
            local
        }
        return updated
    }
}

data class LinovelibSyncResult(
    val syncedBooks: Int = 0,
    val syncedReadingProgress: Int = 0,
    val failedBookIds: List<String> = emptyList(),
    val summary: String = "",
    val error: String? = null
)
