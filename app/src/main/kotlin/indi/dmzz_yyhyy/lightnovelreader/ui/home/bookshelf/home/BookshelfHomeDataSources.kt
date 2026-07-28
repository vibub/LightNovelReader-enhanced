package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import androidx.compose.runtime.Stable
import indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.BookshelfCardSnapshot
import io.nightfish.lightnovelreader.api.bookshelf.BookshelfBookMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Stable
class BookshelfHomeDataSources(
    val cardSnapshot: (String) -> StateFlow<BookshelfCardSnapshot>,
    val metadataFlow: (String) -> Flow<BookshelfBookMetadata?>,
    val updateVisibleWindow: (BookshelfVisibleWindow) -> Unit,
    val requestBookInformation: (List<String>) -> Unit,
)
