package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val ScrollReadingAnchorJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class ScrollReadingAnchor(
    val chapterId: String,
    val itemType: String,
    val componentIndex: Int = -1,
    val itemProgress: Float,
    val itemSize: Int,
    val viewportHeight: Int,
    val componentHeights: Map<Int, Int> = emptyMap(),
    val componentCount: Int = -1
) {
    internal val parsedItemType: ScrollContentItemType?
        get() = runCatching { ScrollContentItemType.valueOf(itemType) }.getOrNull()
}

internal fun encodeScrollReadingAnchor(anchor: ScrollReadingAnchor): String =
    ScrollReadingAnchorJson.encodeToString(anchor)

internal fun decodeScrollReadingAnchor(value: String?): ScrollReadingAnchor? =
    value
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ScrollReadingAnchorJson.decodeFromString<ScrollReadingAnchor>(it) }.getOrNull() }

internal data class ScrollRestoreTarget(
    val itemIndex: Int,
    val itemProgress: Float
)

internal fun scrollContentItemProgress(
    itemOffset: Int,
    itemSize: Int,
    viewportHeight: Int
): Float {
    if (viewportHeight <= 0) return 0f
    return ((viewportHeight - itemOffset).toFloat() / itemSize.coerceAtLeast(1))
        .coerceIn(0f, 1f)
}

internal fun scrollContentChapterProgress(
    key: ParsedScrollContentItemKey?,
    itemOffset: Int,
    itemSize: Int,
    viewportHeight: Int,
    componentCount: Int,
    componentHeights: Map<Int, Int> = emptyMap()
): Float {
    val itemProgress = scrollContentItemProgress(
        itemOffset = itemOffset,
        itemSize = itemSize,
        viewportHeight = viewportHeight
    )
    if (key == null) return itemProgress

    val safeComponentCount = componentCount.coerceAtLeast(0)
    return when (key.type) {
        ScrollContentItemType.Header -> 0f
        ScrollContentItemType.Component -> {
            if (safeComponentCount == 0) {
                0f
            } else {
                val componentIndex = key.index.coerceIn(0, safeComponentCount - 1)
                val weights = scrollContentComponentWeights(
                    componentCount = safeComponentCount,
                    componentHeights = componentHeights,
                    viewportHeight = viewportHeight,
                    currentComponentIndex = componentIndex,
                    currentComponentHeight = itemSize
                )
                val totalHeight = weights.sumOf { it.toLong() }.coerceAtLeast(1L)
                val heightBefore = weights
                    .take(componentIndex)
                    .sumOf { it.toLong() }
                ((heightBefore + weights[componentIndex] * itemProgress) / totalHeight.toFloat())
                    .coerceIn(0f, 1f)
            }
        }
        ScrollContentItemType.Footer -> 1f
    }
}

internal fun scrollContentRestoreTarget(
    progress: Float,
    componentIndices: List<Int>,
    headerIndex: Int?,
    footerIndex: Int?,
    fallbackIndex: Int,
    componentHeights: Map<Int, Int> = emptyMap(),
    defaultComponentHeight: Int = 1
): ScrollRestoreTarget {
    val recovered = progress.coerceIn(0f, 1f)
    if (recovered <= 0f) {
        return ScrollRestoreTarget(
            itemIndex = headerIndex ?: componentIndices.firstOrNull() ?: footerIndex ?: fallbackIndex,
            itemProgress = 0f
        )
    }
    if (recovered >= 1f) {
        return ScrollRestoreTarget(
            itemIndex = footerIndex ?: componentIndices.lastOrNull() ?: headerIndex ?: fallbackIndex,
            itemProgress = 1f
        )
    }
    if (componentIndices.isEmpty()) {
        return ScrollRestoreTarget(
            itemIndex = footerIndex ?: headerIndex ?: fallbackIndex,
            itemProgress = if (footerIndex != null) 1f else 0f
        )
    }

    val weights = scrollContentComponentWeights(
        componentCount = componentIndices.size,
        componentHeights = componentHeights,
        viewportHeight = defaultComponentHeight.coerceAtLeast(1)
    )
    val totalHeight = weights.sumOf { it.toLong() }.coerceAtLeast(1L)
    val targetHeight = totalHeight * recovered
    var heightBefore = 0L
    weights.forEachIndexed { index, height ->
        val nextHeight = heightBefore + height
        if (targetHeight <= nextHeight || index == weights.lastIndex) {
            return ScrollRestoreTarget(
                itemIndex = componentIndices[index],
                itemProgress = ((targetHeight - heightBefore) / height.toFloat()).coerceIn(0f, 1f)
            )
        }
        heightBefore = nextHeight
    }

    return ScrollRestoreTarget(
        itemIndex = componentIndices.last(),
        itemProgress = 1f
    )
}

private fun scrollContentComponentWeights(
    componentCount: Int,
    componentHeights: Map<Int, Int>,
    viewportHeight: Int,
    currentComponentIndex: Int? = null,
    currentComponentHeight: Int? = null
): List<Int> {
    if (componentCount <= 0) return emptyList()
    val validHeights = componentHeights
        .filterKeys { it in 0 until componentCount }
        .mapValues { it.value.coerceAtLeast(1) }
        .toMutableMap()
    if (currentComponentIndex != null && currentComponentIndex in 0 until componentCount && currentComponentHeight != null) {
        val measuredHeight = currentComponentHeight.coerceAtLeast(1)
        validHeights[currentComponentIndex] = maxOf(validHeights[currentComponentIndex] ?: measuredHeight, measuredHeight)
    }
    val fallback = viewportHeight.coerceAtLeast(1)
    return List(componentCount) { index -> validHeights[index] ?: fallback }
}
