package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf

import androidx.compose.runtime.Stable
import io.nightfish.lightnovelreader.api.bookshelf.Bookshelf
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfSortType

@Stable
data class BookshelfUiState(
    val id: Int,
    val name: String,
    val sortType: BookshelfSortType,
    val sortReversed: Boolean,
    val autoCache: Boolean,
    val systemUpdateReminder: Boolean,
    val allBookIds: List<String>,
    val pinnedBookIds: List<String>,
    val updatedBookIds: List<String>
)

fun Bookshelf.toBookshelfUiState() = BookshelfUiState(
    id = id,
    name = name,
    sortType = sortType,
    sortReversed = sortReversed,
    autoCache = autoCache,
    systemUpdateReminder = systemUpdateReminder,
    allBookIds = allBookIds,
    pinnedBookIds = pinnedBookIds,
    updatedBookIds = updatedBookIds
)