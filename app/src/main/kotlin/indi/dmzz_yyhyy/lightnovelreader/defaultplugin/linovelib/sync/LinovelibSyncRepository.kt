package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

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
import io.nightfish.lightnovelreader.api.book.ChapterInformation
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LinovelibSyncRepository @Inject constructor(
    private val userDataRepository: UserDataRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val localBookDataSource: LocalBookDataSource,
    private val webBookDataSourceProvider: WebBookDataSourceProvider,
    private val bookmarkRepository: LinovelibBookmarkRepository
) {
    private val accountStore = LinovelibAccountStore(userDataRepository)
    private val jsoup = LinovelibJsoup(accountStore)
    private val websiteDataSource = LinovelibWebsiteDataSource(jsoup)
    private val accountDataSource = LinovelibAccountDataSource(jsoup, accountStore)

    suspend fun isRemoteBookmarkAt(bookId: String, chapterId: String, chapterTitle: String): Boolean = withContext(Dispatchers.IO) {
        val remoteBook = accountDataSource.getRemoteBookshelf().firstOrNull { it.bookId == bookId }
            ?: return@withContext false
        val remoteChapterId = remoteBook.bookmarkChapterId.substringBefore('_')
        remoteChapterId == chapterId ||
            (chapterTitle.isNotBlank() && remoteBook.bookmarkChapterTitle.normalizeBookmarkTitle() == chapterTitle.normalizeBookmarkTitle())
    }

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
            var bookmarksUpdated = 0
            var unresolvedBookmarks = 0
            val failedBookIds = mutableListOf<String>()
            remoteBooks.forEach { remoteBook ->
                runCatching {
                    val bookInformation = websiteDataSource.getBookInformation(remoteBook.bookId)
                    if (bookInformation.isEmpty()) error("书籍详情为空")
                    localBookDataSource.updateBookInformation(bookInformation)
                    bookshelfRepository.addBookIntoBookShelf(LinovelibConstants.SYNC_BOOKSHELF_ID, bookInformation)
                    addedOrUpdatedBooks++

                    if (remoteBook.hasBookmark()) {
                        val volumes = websiteDataSource.getBookVolumes(remoteBook.bookId)
                        if (!volumes.isEmpty()) localBookDataSource.updateBookVolumes(volumes)
                        val bookmark = resolveBookmark(remoteBook, volumes)
                        bookmarkRepository.upsertRemoteBookmark(
                            bookId = remoteBook.bookId,
                            chapterId = bookmark?.id.orEmpty(),
                            chapterTitle = bookmark?.title ?: remoteBook.bookmarkChapterTitle,
                            resolved = bookmark != null
                        )
                        if (bookmark == null) unresolvedBookmarks++ else bookmarksUpdated++
                    }
                }.onFailure {
                    it.printStackTrace()
                    failedBookIds += remoteBook.bookId
                }
            }
            val now = LocalDateTime.now().toString()
            val summary = "同步完成：书籍 $addedOrUpdatedBooks 本，章节书签 $bookmarksUpdated 本，待解析 $unresolvedBookmarks 本，失败 ${failedBookIds.size} 本"
            accountStore.markSyncSuccess(now, summary)
            LinovelibSyncResult(
                syncedBooks = addedOrUpdatedBooks,
                syncedReadingProgress = 0,
                syncedBookmarks = bookmarksUpdated,
                unresolvedBookmarks = unresolvedBookmarks,
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

    private fun resolveBookmark(
        remoteBook: LinovelibAccountDataSource.LinovelibRemoteBook,
        volumes: BookVolumes
    ): ChapterInformation? {
        val chapters = volumes.volumes.flatMap { it.chapters }
        if (chapters.isEmpty()) return null
        remoteBook.bookmarkChapterId.takeIf { it.isNotBlank() }?.let { remoteId ->
            chapters.firstOrNull { it.id == remoteId || it.id == remoteId.substringBefore('_') }?.let { return it }
        }
        val remoteTitle = remoteBook.bookmarkChapterTitle.normalizeBookmarkTitle()
        if (remoteTitle.isBlank()) return null
        chapters.firstOrNull { it.title.normalizeBookmarkTitle() == remoteTitle }?.let { return it }
        return chapters.firstOrNull { chapter ->
            val title = chapter.title.normalizeBookmarkTitle()
            title.isNotBlank() && (remoteTitle.contains(title) || title.contains(remoteTitle))
        }
    }

    private fun LinovelibAccountDataSource.LinovelibRemoteBook.hasBookmark(): Boolean =
        bookmarkChapterId.isNotBlank() || bookmarkChapterTitle.isNotBlank()

    private fun String.normalizeBookmarkTitle(): String =
        replace('　', ' ')
            .replace(Regex("\\s+"), "")
            .replace(Regex("^[书签章节阅读至读到看到继续上次最近：:]+"), "")
            .replace("：", ":")
            .trim()
}

data class LinovelibSyncResult(
    val syncedBooks: Int = 0,
    val syncedReadingProgress: Int = 0,
    val syncedBookmarks: Int = 0,
    val unresolvedBookmarks: Int = 0,
    val failedBookIds: List<String> = emptyList(),
    val summary: String = "",
    val error: String? = null
)
