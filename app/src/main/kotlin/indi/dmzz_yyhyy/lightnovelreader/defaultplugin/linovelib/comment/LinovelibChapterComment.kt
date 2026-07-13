package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment

data class LinovelibChapterComment(
    val id: String,
    val username: String,
    val avatarUrl: String,
    val userProfileUrl: String,
    val publishedAt: String,
    val honor: String,
    val body: String,
    val quotedReplies: List<LinovelibCommentQuote>,
    val likeCount: Int,
    val dislikeCount: Int,
    val isSpoiler: Boolean
)

data class LinovelibCommentQuote(
    val username: String,
    val body: String
)

data class LinovelibCommentPage(
    val comments: List<LinovelibChapterComment>,
    val totalCount: Int,
    val participantCount: Int,
    val pageIndex: Int,
    val pageTotal: Int,
    val hasMore: Boolean
)

enum class LinovelibCommentQuery {
    Hot,
    All
}

class LinovelibCommentProtocolException(
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)
