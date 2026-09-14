package indi.dmzz_yyhyy.lightnovelreader.data.content.component

/** 固定高度的文本块索引；滚动时只查找可见范围，不重新累加整章高度。 */
internal class TextBlockIndex(heights: List<Int>) {
    private val offsets = IntArray(heights.size + 1)
    val size: Int get() = offsets.size - 1
    val height: Int get() = offsets.last()

    init {
        heights.forEachIndexed { index, height ->
            require(height >= 0)
            offsets[index + 1] = Math.addExact(offsets[index], height)
        }
    }

    fun top(index: Int): Int = offsets[index]

    /** 接近缓存边缘才移动窗口，避免短距离反向滑动反复释放同一批文本块。 */
    fun retainedRange(current: IntRange, top: Float, bottom: Float): IntRange {
        val viewportHeight = bottom - top
        if (!viewportHeight.isFinite() || viewportHeight <= 0f) return IntRange.EMPTY
        val target = visibleRange(top, bottom, viewportHeight * 2f)
        if (target.isEmpty()) return target
        val guard = visibleRange(top, bottom, viewportHeight * 0.5f)
        if (!current.isEmpty() && current.first >= 0 && current.last < size) {
            if (!guard.isEmpty() && guard.first >= current.first && guard.last <= current.last) return current
            if (guard.isEmpty() && target.first <= current.last && current.first <= target.last) return current
        }
        return target
    }

    /** 只新增可见块；附近缓存仅保留已创建的选择节点，不主动补齐屏外节点。 */
    fun selectableRange(current: IntRange, top: Float, bottom: Float): IntRange = retainTextBlocks(
        current, visibleRange(top, bottom), retainedRange(current, top, bottom)
    )

    fun visibleRange(top: Float, bottom: Float, overscan: Float = 0f): IntRange {
        require(overscan >= 0f && overscan.isFinite())
        if (size == 0 || height == 0 || !top.isFinite() || !bottom.isFinite() || bottom <= top) {
            return IntRange.EMPTY
        }
        val start = top - overscan
        val end = bottom + overscan
        if (end <= 0f || start >= height) return IntRange.EMPTY

        // 首个底边超过上边界的块，跳过边界上的零高度空白。
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offsets[middle + 1] <= start) low = middle + 1 else high = middle
        }
        val first = low
        // 首个顶边达到下边界的块不属于可见区。
        high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offsets[middle] < end) low = middle + 1 else high = middle
        }
        return first until low
    }
}
