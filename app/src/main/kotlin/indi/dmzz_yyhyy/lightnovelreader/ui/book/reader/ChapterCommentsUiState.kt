package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterComment

enum class ChapterCommentTab {
    Hot,
    All
}

sealed interface ChapterCommentError {
    data object Network : ChapterCommentError
    data object RateLimited : ChapterCommentError
    data object Cloudflare : ChapterCommentError
    data object LoginRequired : ChapterCommentError
    data object Protocol : ChapterCommentError
}

data class ChapterEndContext(
    val bookId: String,
    val chapterId: String,
    val chapterTitle: String,
    val refererChapterPageId: String
)

data class ChapterCommentsUiState(
    val isVisible: Boolean = false,
    val context: ChapterEndContext? = null,
    val selectedTab: ChapterCommentTab = ChapterCommentTab.Hot,
    val hotComments: List<LinovelibChapterComment> = emptyList(),
    val allComments: List<LinovelibChapterComment> = emptyList(),
    val totalCount: Int? = null,
    val participantCount: Int? = null,
    val hasCookie: Boolean = false,
    val cookieExpired: Boolean = false,
    val isLoadingHot: Boolean = false,
    val isLoadingAll: Boolean = false,
    val hasMoreAll: Boolean = false,
    val nextAllPage: Int = 1,
    val hotError: ChapterCommentError? = null,
    val allError: ChapterCommentError? = null
)
