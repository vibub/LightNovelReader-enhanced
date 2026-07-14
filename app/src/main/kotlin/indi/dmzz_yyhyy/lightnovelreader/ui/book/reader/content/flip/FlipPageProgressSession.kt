package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

internal data class FlipPagePagerSession(
    val chapterId: String,
    val visitId: Long,
    val pagerId: Long,
    val contentPageCount: Int
)

internal data class FlipPageRestoreRequest(
    val session: FlipPagePagerSession,
    val progress: Float
)

internal data class FlipPagePagerInstallation(
    val session: FlipPagePagerSession,
    val restoreRequest: FlipPageRestoreRequest?
)

internal class FlipPageProgressSession {
    private var requestedChapterId = ""
    private var visitId = 0L
    private var pagerId = 0L
    private var activePagerSession: FlipPagePagerSession? = null
    private var restoredPagerId = 0L
    private var loadedRestoreProgress: Float? = null
    private var currentProgress: Float? = null

    fun beginChapter(chapterId: String): Long {
        requestedChapterId = chapterId
        visitId += 1
        activePagerSession = null
        restoredPagerId = 0L
        loadedRestoreProgress = null
        currentProgress = null
        return visitId
    }

    fun installPager(
        chapterId: String,
        contentPageCount: Int
    ): FlipPagePagerInstallation? {
        if (chapterId.isBlank() || chapterId != requestedChapterId) return null
        val session = FlipPagePagerSession(
            chapterId = chapterId,
            visitId = visitId,
            pagerId = ++pagerId,
            contentPageCount = contentPageCount.coerceAtLeast(0)
        )
        activePagerSession = session
        restoredPagerId = 0L
        return FlipPagePagerInstallation(
            session = session,
            restoreRequest = restoreRequest(session)
        )
    }

    fun loadRestoreProgress(
        visitId: Long,
        chapterId: String,
        progress: Float
    ): FlipPageRestoreRequest? {
        if (visitId != this.visitId || chapterId != requestedChapterId) return null
        loadedRestoreProgress = progress.normalizedProgress()
        return activePagerSession?.let(::restoreRequest)
    }

    fun completeRestore(request: FlipPageRestoreRequest): Boolean {
        if (!isCurrent(request.session)) return false
        currentProgress = request.progress.normalizedProgress()
        restoredPagerId = request.session.pagerId
        return true
    }

    fun acceptProgress(
        session: FlipPagePagerSession,
        progress: Float
    ): Boolean {
        if (!isCurrent(session) || restoredPagerId != session.pagerId) return false
        currentProgress = progress.normalizedProgress()
        return true
    }

    fun isCurrent(session: FlipPagePagerSession): Boolean =
        activePagerSession == session &&
            session.chapterId == requestedChapterId &&
            session.visitId == visitId

    private fun restoreRequest(session: FlipPagePagerSession): FlipPageRestoreRequest? {
        if (session.contentPageCount <= 0) return null
        val progress = currentProgress ?: loadedRestoreProgress ?: return null
        return FlipPageRestoreRequest(
            session = session,
            progress = progress.normalizedProgress()
        )
    }
}

private fun Float.normalizedProgress(): Float =
    if (isFinite()) coerceIn(0f, 1f) else 0f
