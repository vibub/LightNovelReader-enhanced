package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import kotlin.math.roundToInt

internal fun flipReadingProgress(
    settledPage: Int,
    contentPageCount: Int
): Float {
    if (contentPageCount <= 0) return 0f
    val contentPage = settledPage.coerceIn(0, contentPageCount - 1)
    return ((contentPage + 1) / contentPageCount.toFloat()).coerceIn(0f, 1f)
}

internal fun flipRestoreContentPage(
    progress: Float,
    contentPageCount: Int
): Int {
    if (contentPageCount <= 0 || !progress.isFinite()) return 0
    val normalizedProgress = progress.coerceIn(0f, 1f)
    return ((contentPageCount * normalizedProgress).roundToInt() - 1)
        .coerceIn(0, contentPageCount - 1)
}
