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
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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

    suspend fun syncBookmarkToRemote(
        bookId: String,
        chapterPageId: String
    ): LinovelibRemoteBookmarkResult = withContext(Dispatchers.IO) {
        if (webBookDataSourceProvider.default.id != LinovelibConstants.SOURCE_ID) {
            val message = "请先切换到 Linovelib 数据源后再同步"
            return@withContext LinovelibRemoteBookmarkResult(success = false, message = message)
        }
        if (!accountStore.hasCookie()) {
            val message = "尚未保存 Linovelib 登录 Cookie"
            return@withContext LinovelibRemoteBookmarkResult(success = false, message = message)
        }
        val target = LinovelibRemoteBookmarkTarget.from(bookId, chapterPageId)
        if (target == null) {
            return@withContext LinovelibRemoteBookmarkResult(success = false, message = "章节书签参数无效")
        }

        runCatching {
            val response = accountDataSource.addBookcaseBookmark(
                bookId = target.bookId,
                chapterId = target.chapterId,
                page = target.page,
                refererChapterPageId = target.chapterPageId
            )
            val responseMessage = response.toBookmarkResponseMessage()
            if (responseMessage.isLoginRequiredBookmarkMessage()) {
                error("Linovelib Cookie 可能已失效，请重新登录并保存 Cookie")
            }
            if (responseMessage.isSuccessfulBookmarkMessage()) {
                LinovelibRemoteBookmarkResult(
                    success = true,
                    message = responseMessage.ifBlank { "章节书签已同步到 Linovelib" }
                )
            } else {
                LinovelibRemoteBookmarkResult(
                    success = false,
                    message = responseMessage.ifBlank { "Linovelib 未返回添加书签成功信息" }
                )
            }
        }.getOrElse { throwable ->
            LinovelibRemoteBookmarkResult(
                success = false,
                message = throwable.message ?: throwable.javaClass.simpleName
            )
        }
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
            val failedBookIds = mutableListOf<String>()
            remoteBooks.forEach { remoteBook ->
                runCatching {
                    val bookInformation = getBookInformationForSync(remoteBook)
                    localBookDataSource.updateBookInformation(bookInformation)
                    bookshelfRepository.addBookIntoBookShelf(LinovelibConstants.SYNC_BOOKSHELF_ID, bookInformation)
                    addedOrUpdatedBooks++

                    if (remoteBook.hasBookmark()) {
                        remoteBookmarks++
                        val directBookmark = LinovelibRemoteBookmarkSyncResolver.resolveDirect(remoteBook)
                        if (directBookmark != null) {
                            bookmarkRepository.upsertRemoteBookmark(
                                bookId = remoteBook.bookId,
                                chapterId = directBookmark.chapterId,
                                chapterTitle = directBookmark.chapterTitle,
                                resolved = true
                            )
                            bookmarksUpdated++
                        } else {
                            val bookmark = LinovelibRemoteBookmarkSyncResolver.resolveWithVolumes(
                                remoteBook = remoteBook,
                                volumes = getBookVolumesForSync(remoteBook.bookId)
                            )
                            bookmarkRepository.upsertRemoteBookmark(
                                bookId = remoteBook.bookId,
                                chapterId = bookmark.chapterId,
                                chapterTitle = bookmark.chapterTitle,
                                resolved = bookmark.resolved
                            )
                            if (bookmark.resolved) bookmarksUpdated++ else unresolvedBookmarks++
                        }
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
                failedBookIds = failedBookIds
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

    private suspend fun getBookVolumesForSync(bookId: String): BookVolumes {
        val remoteVolumes = websiteDataSource.getBookVolumes(bookId)
        if (!remoteVolumes.isEmpty()) {
            localBookDataSource.updateBookVolumes(remoteVolumes)
        }
        return remoteVolumes
    }

    private fun LinovelibAccountDataSource.LinovelibRemoteBook.toMinimalBookInformation(): BookInformation =
        BookInformation.empty(bookId).toMutable().apply {
            title = this@toMinimalBookInformation.title.ifBlank { "Linovelib $bookId" }
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

    private fun LinovelibAccountDataSource.LinovelibRemoteBook.hasBookmark(): Boolean =
        bookmarkChapterId.isNotBlank() || bookmarkChapterTitle.isNotBlank()

    private fun buildSyncSummary(
        remoteBookshelf: LinovelibAccountDataSource.LinovelibRemoteBookshelf,
        addedOrUpdatedBooks: Int,
        remoteBookmarks: Int,
        bookmarksUpdated: Int,
        unresolvedBookmarks: Int,
        failedBookIds: List<String>
    ): String = buildString {
        append("同步完成：远端书架 ")
        append(remoteBookshelf.books.size)
        remoteBookshelf.expectedGroupCount?.let { append("/").append(it) }
        append(" 本，入库 ").append(addedOrUpdatedBooks).append(" 本")
        append("，远端书签 ").append(remoteBookmarks).append(" 本")
        append("，章节书签 ").append(bookmarksUpdated).append(" 本")
        append("，待解析 ").append(unresolvedBookmarks).append(" 本")
        append("，失败 ").append(failedBookIds.size).append(" 本")
        if (remoteBookshelf.pagesFetched > 1) {
            append("，读取 ").append(remoteBookshelf.pagesFetched).append(" 页")
        }
    }

}

internal object LinovelibRemoteBookmarkSyncResolver {
    fun resolveDirect(
        remoteBook: LinovelibAccountDataSource.LinovelibRemoteBook
    ): LinovelibResolvedRemoteBookmark? {
        val chapterId = remoteBook.bookmarkChapterId.trim()
            .takeIf { it.isNotBlank() && it.substringBefore('_') != "0" }
            ?: return null
        return LinovelibResolvedRemoteBookmark(
            chapterId = chapterId,
            chapterTitle = remoteBook.bookmarkChapterTitle,
            resolved = true
        )
    }

    fun resolveWithVolumes(
        remoteBook: LinovelibAccountDataSource.LinovelibRemoteBook,
        volumes: BookVolumes
    ): LinovelibResolvedRemoteBookmark {
        val bookmark = LinovelibBookmarkMatcher.resolve(
            remoteChapterId = "",
            remoteTitle = remoteBook.bookmarkChapterTitle,
            volumes = volumes
        )
        return LinovelibResolvedRemoteBookmark(
            chapterId = bookmark?.id.orEmpty(),
            chapterTitle = bookmark?.title ?: remoteBook.bookmarkChapterTitle,
            resolved = bookmark != null
        )
    }
}

internal data class LinovelibResolvedRemoteBookmark(
    val chapterId: String,
    val chapterTitle: String,
    val resolved: Boolean
)

data class LinovelibSyncResult(
    val syncedBooks: Int = 0,
    val syncedReadingProgress: Int = 0,
    val syncedBookmarks: Int = 0,
    val unresolvedBookmarks: Int = 0,
    val failedBookIds: List<String> = emptyList(),
    val summary: String = "",
    val error: String? = null
)

data class LinovelibRemoteBookmarkResult(
    val success: Boolean,
    val message: String
)

internal data class LinovelibRemoteBookmarkTarget(
    val bookId: String,
    val chapterId: String,
    val page: Int,
    val chapterPageId: String
) {
    val addBookcaseUrl: String = LinovelibConstants.addBookcaseUrl(bookId, chapterId, page)
    val referer: String = LinovelibConstants.chapterUrl(bookId, chapterPageId)

    companion object {
        fun from(bookId: String, chapterPageId: String): LinovelibRemoteBookmarkTarget? {
            val normalizedBookId = LinovelibConstants.run { bookId.normalizeBookId() }
            val normalizedChapterPageId = LinovelibConstants.run { chapterPageId.normalizeChapterId() }
            if (normalizedBookId.isBlank() || normalizedChapterPageId.isBlank()) return null

            val chapterId = normalizedChapterPageId.substringBefore('_')
                .takeIf { it.isNotBlank() && it.all { char -> char.isDigit() } }
                ?: return null
            val pageSuffix = normalizedChapterPageId.substringAfter('_', missingDelimiterValue = "")
            val page = if ('_' in normalizedChapterPageId) {
                pageSuffix.toIntOrNull()?.takeIf { it > 0 } ?: return null
            } else {
                1
            }
            val normalizedPageId = if (page <= 1) chapterId else "${chapterId}_$page"
            return LinovelibRemoteBookmarkTarget(
                bookId = normalizedBookId,
                chapterId = chapterId,
                page = page,
                chapterPageId = normalizedPageId
            )
        }
    }
}

private fun String.toBookmarkResponseMessage(): String = replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    .replace(Regex("\\n{3,}"), "\n\n")
    .trim()

private fun String.isLoginRequiredBookmarkMessage(): Boolean =
    listOf("请先登录", "請先登入", "会员登录", "會員登入", "登录后", "登入後").any { it in this }

private fun String.isSuccessfulBookmarkMessage(): Boolean {
    if (isBlank()) return false
    if (listOf("失败", "失敗", "错误", "錯誤", "请先", "請先").any { it in this }) return false
    return listOf("成功", "已加入", "已添加", "书签", "書籤", "书架", "書架").any { it in this }
}
