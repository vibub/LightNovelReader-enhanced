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
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds

@Singleton
class LinovelibSyncRepository @Inject constructor(
    userDataRepository: UserDataRepository,
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
        val remoteIds = LinovelibBookmarkMatcher.chapterIdCandidates(remoteBook.bookmarkChapterId)
        val localIds = LinovelibBookmarkMatcher.chapterIdCandidates(chapterId)
        if (remoteIds.isNotEmpty() && localIds.isNotEmpty() && remoteIds.any { it in localIds }) {
            return@withContext true
        }
        chapterTitle.isNotBlank() && LinovelibBookmarkMatcher.matchesTitle(remoteBook.bookmarkChapterTitle, chapterTitle)
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
            val remoteBookshelf = accountDataSource.getRemoteBookshelfResult()
            val remoteBooks = remoteBookshelf.books
            var addedOrUpdatedBooks = 0
            var bookmarksUpdated = 0
            var unresolvedBookmarks = 0
            var remoteBookmarks = 0
            var volumeFallbacks = 0
            val failedBookIds = mutableListOf<String>()
            remoteBooks.forEach { remoteBook ->
                runCatching {
                    val bookInformation = getBookInformationForSync(remoteBook)
                    localBookDataSource.updateBookInformation(bookInformation)
                    bookshelfRepository.addBookIntoBookShelf(LinovelibConstants.SYNC_BOOKSHELF_ID, bookInformation)
                    addedOrUpdatedBooks++

                    if (remoteBook.hasBookmark()) {
                        remoteBookmarks++
                        val volumesForSync = getBookVolumesForSync(remoteBook.bookId)
                        if (volumesForSync.usedLocalFallback) volumeFallbacks++
                        val bookmark = resolveBookmark(remoteBook, volumesForSync.volumes)
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
            val now = Instant.now().toString()
            val summary = buildSyncSummary(
                remoteBookshelf = remoteBookshelf,
                addedOrUpdatedBooks = addedOrUpdatedBooks,
                remoteBookmarks = remoteBookmarks,
                bookmarksUpdated = bookmarksUpdated,
                unresolvedBookmarks = unresolvedBookmarks,
                failedBookIds = failedBookIds,
                volumeFallbacks = volumeFallbacks
            )
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

    private suspend fun getBookInformationForSync(
        remoteBook: LinovelibAccountDataSource.LinovelibRemoteBook
    ): BookInformation {
        localBookDataSource.getBookInformation(remoteBook.bookId)
            ?.takeIf { !it.isEmpty() }
            ?.let { return it }
        return remoteBook.toMinimalBookInformation()
    }

    private suspend fun getBookVolumesForSync(bookId: String): BookVolumesForSync {
        delay(SYNC_REQUEST_DELAY_MILLIS.milliseconds)
        val remoteVolumes = websiteDataSource.getBookVolumes(bookId)
        if (!remoteVolumes.isEmpty()) {
            localBookDataSource.updateBookVolumes(remoteVolumes)
            return BookVolumesForSync(remoteVolumes, usedLocalFallback = false)
        }
        val localVolumes = localBookDataSource.getBookVolumes(bookId)
            ?.takeIf { !it.isEmpty() }
            ?: BookVolumes.empty(bookId)
        return BookVolumesForSync(localVolumes, usedLocalFallback = !localVolumes.isEmpty())
    }

    private fun LinovelibAccountDataSource.LinovelibRemoteBook.toMinimalBookInformation(): BookInformation =
        BookInformation.empty(bookId).toMutable().apply {
            title = this@toMinimalBookInformation.title.ifBlank { "Linovelib $bookId" }
        }

    private data class BookVolumesForSync(
        val volumes: BookVolumes,
        val usedLocalFallback: Boolean
    )

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
    ) = LinovelibBookmarkMatcher.resolve(
        remoteChapterId = remoteBook.bookmarkChapterId.ifBlank { remoteBook.bookmarkHref },
        remoteTitle = remoteBook.bookmarkChapterTitle,
        volumes = volumes
    )

    private fun LinovelibAccountDataSource.LinovelibRemoteBook.hasBookmark(): Boolean =
        bookmarkChapterId.isNotBlank() || bookmarkChapterTitle.isNotBlank()

    private fun buildSyncSummary(
        remoteBookshelf: LinovelibAccountDataSource.LinovelibRemoteBookshelf,
        addedOrUpdatedBooks: Int,
        remoteBookmarks: Int,
        bookmarksUpdated: Int,
        unresolvedBookmarks: Int,
        failedBookIds: List<String>,
        volumeFallbacks: Int
    ): String = buildString {
        append("同步完成：远端书架 ")
        append(remoteBookshelf.books.size)
        remoteBookshelf.expectedGroupCount?.let { append("/").append(it) }
        append(" 本，入库 ").append(addedOrUpdatedBooks).append(" 本")
        append("，远端书签 ").append(remoteBookmarks).append(" 本")
        append("，章节书签 ").append(bookmarksUpdated).append(" 本")
        append("，待解析 ").append(unresolvedBookmarks).append(" 本")
        append("，失败 ").append(failedBookIds.size).append(" 本")
        if (volumeFallbacks > 0) {
            append("，目录回退 ").append(volumeFallbacks).append(" 本")
        }
        if (remoteBookshelf.pagesFetched > 1) {
            append("，读取 ").append(remoteBookshelf.pagesFetched).append(" 页")
        }
    }

    private companion object {
        private const val SYNC_REQUEST_DELAY_MILLIS = 2_000L
    }
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
