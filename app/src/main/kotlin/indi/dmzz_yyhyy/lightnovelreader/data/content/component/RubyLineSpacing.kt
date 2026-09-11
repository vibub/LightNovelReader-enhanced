package indi.dmzz_yyhyy.lightnovelreader.data.content.component

internal data class RubyLineSpace(
    val height: Int,
    val isBlank: Boolean,
    val extraAbove: Int = 0
)

/** 将注释顶开的正文高度抵扣到前面的连续空行，不跨越正文，也不产生负高度。 */
internal fun reusableRubySpacing(lines: List<RubyLineSpace>): List<Int> {
    val reclaimed = MutableList(lines.size) { 0 }
    lines.forEachIndexed { index, line ->
        if (line.isBlank) return@forEachIndexed
        var remaining = line.extraAbove.coerceAtLeast(0)
        var previous = index - 1
        while (remaining > 0 && previous >= 0 && lines[previous].isBlank) {
            val available = (lines[previous].height - reclaimed[previous]).coerceAtLeast(0)
            val used = minOf(available, remaining)
            reclaimed[previous] += used
            remaining -= used
            previous--
        }
    }
    return reclaimed
}

internal data class RubyPageLine(
    val start: Int,
    val end: Int,
    val height: Int,
    val isBlank: Boolean,
    val reclaimedHeight: Int = 0
)

internal fun rubyPageRanges(lines: List<RubyPageLine>, maxHeight: Int): List<IntRange> = buildList {
    var pageStart = 0
    var pageEnd = 0
    var usedHeight = 0
    fun append(start: Int, end: Int, height: Int) {
        if (pageEnd > pageStart && usedHeight + height > maxHeight) {
            add(pageStart until pageEnd)
            pageStart = start
            usedHeight = 0
        }
        pageEnd = end
        usedHeight += height
    }
    var index = 0
    while (index < lines.size) {
        val groupStart = index
        // 借出的空白与使用它的注释行一起分页，避免页首重新测量时失去这部分空间。
        if (lines[index].reclaimedHeight > 0) {
            while (index < lines.lastIndex && lines[index].isBlank) index++
        }
        val group = lines.subList(groupStart, index + 1)
        val height = group.sumOf { it.height }
        if (height > maxHeight && group.size > 1) {
            // 极长的小节留白无法整组放入一页时，按未抵扣的高度保守分页。
            group.forEach { append(it.start, it.end, it.height + it.reclaimedHeight) }
        } else {
            append(group.first().start, group.last().end, height)
        }
        index++
    }
    if (pageEnd > pageStart) add(pageStart until pageEnd)
}
