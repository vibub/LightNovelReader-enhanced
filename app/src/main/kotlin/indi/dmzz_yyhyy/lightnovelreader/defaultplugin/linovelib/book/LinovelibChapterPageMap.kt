package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class LinovelibChapterPageBoundary(
    val chapterId: String,
    val startWeight: Int,
    val endWeight: Int
)

internal fun List<LinovelibChapterContentParser.Part>.linovelibContentWeight(): Int = sumOf { part ->
    when (part) {
        is LinovelibChapterContentParser.Part.Text -> part.text.length
        is LinovelibChapterContentParser.Part.Image -> LINOVELIB_IMAGE_WEIGHT
        LinovelibChapterContentParser.Part.SectionBreak -> 0
    }
}

internal fun JsonObject.withLinovelibChapterPageMap(
    boundaries: List<LinovelibChapterPageBoundary>
): JsonObject {
    val validBoundaries = boundaries.filter { boundary ->
        boundary.chapterId.isNotBlank() && boundary.endWeight > boundary.startWeight
    }
    if (validBoundaries.isEmpty()) return this
    return buildJsonObject {
        this@withLinovelibChapterPageMap.forEach { (key, value) -> put(key, value) }
        putJsonArray(LINOVELIB_CHAPTER_PAGE_MAP_KEY) {
            validBoundaries.forEach { boundary ->
                addJsonObject {
                    put("chapterId", boundary.chapterId)
                    put("startWeight", boundary.startWeight)
                    put("endWeight", boundary.endWeight)
                }
            }
        }
    }
}

internal fun JsonObject.linovelibChapterPageMap(): List<LinovelibChapterPageBoundary> =
    runCatching {
        this[LINOVELIB_CHAPTER_PAGE_MAP_KEY]
            ?.jsonArray
            ?.mapNotNull { element ->
                val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val chapterId = obj["chapterId"]?.jsonPrimitive?.content.orEmpty()
                val startWeight = obj["startWeight"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val endWeight = obj["endWeight"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                LinovelibChapterPageBoundary(chapterId, startWeight, endWeight)
                    .takeIf { it.chapterId.isNotBlank() && it.endWeight > it.startWeight }
            }
            .orEmpty()
    }.getOrDefault(emptyList())

internal fun JsonObject.targetLinovelibChapterPageId(
    fallbackChapterId: String,
    readingProgress: Float
): String {
    val fallbackBaseChapterId = fallbackChapterId.substringBefore('_')
    val boundaries = linovelibChapterPageMap()
    if (boundaries.isEmpty()) return fallbackBaseChapterId

    val totalWeight = boundaries.maxOf { it.endWeight }.coerceAtLeast(1)
    val progress = if (readingProgress.isNaN()) 0f else readingProgress.coerceIn(0f, 1f)
    val targetWeight = if (progress >= 1f) {
        totalWeight - 1
    } else {
        (totalWeight * progress).toInt().coerceIn(0, totalWeight - 1)
    }
    return boundaries.firstOrNull { boundary ->
        targetWeight >= boundary.startWeight && targetWeight < boundary.endWeight
    }?.chapterId ?: boundaries.last().chapterId
}

internal fun JsonObject.lastLinovelibChapterPageId(fallbackChapterId: String): String {
    val fallbackBaseChapterId = LinovelibConstants.run {
        fallbackChapterId.normalizeChapterId().substringBefore('_')
    }
    return linovelibChapterPageMap()
        .lastOrNull()
        ?.chapterId
        ?.takeIf { it.isNotBlank() }
        ?: fallbackBaseChapterId
}

private const val LINOVELIB_CHAPTER_PAGE_MAP_KEY = "linovelibChapterPageMap"
private const val LINOVELIB_IMAGE_WEIGHT = 800
