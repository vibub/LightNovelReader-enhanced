package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import androidx.compose.runtime.Immutable

internal enum class BookshelfBookSection {
    Updated,
    Pinned,
    All,
}

internal data class BookshelfBookListItem(
    val section: BookshelfBookSection,
    val id: String,
)

@Immutable
data class BookshelfVisibleWindow(
    val detailBookIds: Set<String> = emptySet(),
    val updatedBookIds: Set<String> = emptySet(),
)

internal fun parseBookshelfBookListItemKey(key: Any?): BookshelfBookListItem? {
    val value = key?.toString() ?: return null
    return when {
        value.startsWith("updated_") -> BookshelfBookListItem(
            section = BookshelfBookSection.Updated,
            id = value.removePrefix("updated_")
        )
        value.startsWith("pinned_") -> BookshelfBookListItem(
            section = BookshelfBookSection.Pinned,
            id = value.removePrefix("pinned_")
        )
        value.startsWith("book_") -> BookshelfBookListItem(
            section = BookshelfBookSection.All,
            id = value.removePrefix("book_")
        )
        else -> null
    }?.takeIf { it.id.isNotBlank() }
}

internal fun createBookshelfVisibleWindow(
    visibleItemKeys: List<Any?>,
    updatedBookIds: List<String>,
    updatedExpanded: Boolean,
    pinnedBookIds: List<String>,
    pinnedExpanded: Boolean,
    allBookIds: List<String>,
    allExpanded: Boolean,
    prefetchDistance: Int = 4,
    initialWindowSize: Int = 8,
): BookshelfVisibleWindow {
    require(prefetchDistance >= 0)
    require(initialWindowSize >= 0)

    val sections = listOf(
        BookshelfVisibleSection(
            section = BookshelfBookSection.Updated,
            bookIds = updatedBookIds,
            expanded = updatedExpanded,
        ),
        BookshelfVisibleSection(
            section = BookshelfBookSection.Pinned,
            bookIds = pinnedBookIds,
            expanded = pinnedExpanded,
        ),
        BookshelfVisibleSection(
            section = BookshelfBookSection.All,
            bookIds = allBookIds,
            expanded = allExpanded,
        ),
    )

    val detailIds = linkedSetOf<String>()
    val updatedIds = linkedSetOf<String>()
    val visibleItems = visibleItemKeys.mapNotNull(::parseBookshelfBookListItemKey)
        .filter { item ->
            sections.firstOrNull { it.section == item.section }
                ?.let { it.expanded && item.id in it.bookIds } == true
        }

    if (visibleItems.isEmpty()) {
        sections.firstOrNull { it.expanded && it.bookIds.isNotEmpty() }
            ?.bookIds
            ?.take(initialWindowSize)
            ?.forEach { id ->
                detailIds += id
                if (sections.first { it.expanded && it.bookIds.contains(id) }.section == BookshelfBookSection.Updated) {
                    updatedIds += id
                }
            }
        return BookshelfVisibleWindow(detailIds, updatedIds)
    }

    visibleItems.forEach { item ->
        val section = sections.first { it.section == item.section }
        val index = section.bookIds.indexOf(item.id)
        if (index < 0) return@forEach
        val start = (index - prefetchDistance).coerceAtLeast(0)
        val end = (index + prefetchDistance).coerceAtMost(section.bookIds.lastIndex)
        section.bookIds.subList(start, end + 1).forEach { id ->
            detailIds += id
            if (section.section == BookshelfBookSection.Updated) {
                updatedIds += id
            }
        }
    }

    return BookshelfVisibleWindow(detailIds, updatedIds)
}

internal fun shouldKeepExistingBookInformation(
    hasExisting: Boolean,
    remoteSucceeded: Boolean,
): Boolean = hasExisting || remoteSucceeded

internal fun mergeLatestChapterTitle(
    previousTitle: String?,
    requestedTitle: String?,
    requestSucceeded: Boolean,
): String? = if (requestSucceeded) requestedTitle else previousTitle

private data class BookshelfVisibleSection(
    val section: BookshelfBookSection,
    val bookIds: List<String>,
    val expanded: Boolean,
)
