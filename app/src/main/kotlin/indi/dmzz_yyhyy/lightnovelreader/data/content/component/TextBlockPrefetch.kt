package indi.dmzz_yyhyy.lightnovelreader.data.content.component

/** 立即补齐可见区，保留相连的已有缓存；跳转时不批量创建中间的屏外块。 */
internal fun retainTextBlocks(active: IntRange, required: IntRange, target: IntRange): IntRange {
    if (target.isEmpty()) return IntRange.EMPTY
    val kept = maxOf(active.first, target.first)..minOf(active.last, target.last)
    if (required.isEmpty()) return if (kept.isEmpty()) IntRange.EMPTY else kept
    val needed = maxOf(required.first, target.first)..minOf(required.last, target.last)
    if (kept.isEmpty()) return needed
    if (needed.isEmpty()) return kept
    if (kept.last.toLong() + 1 < needed.first || needed.last.toLong() + 1 < kept.first) return needed
    return minOf(kept.first, needed.first)..maxOf(kept.last, needed.last)
}

/** 每帧最多补一个缓存块，优先补足距离可见区较近的一侧。 */
internal fun prefetchTextBlock(active: IntRange, required: IntRange, target: IntRange): IntRange {
    if (target.isEmpty() || active == target) return target
    if (active.isEmpty()) {
        val first = if (required.isEmpty()) target.first else required.first.coerceIn(target)
        return first..first
    }
    val needsBefore = active.first > target.first
    val needsAfter = active.last < target.last
    val before = if (required.isEmpty()) 0 else required.first - active.first
    val after = if (required.isEmpty()) 0 else active.last - required.last
    return if (needsBefore && (!needsAfter || before < after)) {
        (active.first - 1)..active.last
    } else if (needsAfter) {
        active.first..(active.last + 1)
    } else active
}
