package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf

import androidx.compose.runtime.Immutable
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import io.nightfish.lightnovelreader.api.book.BookInformation
import io.nightfish.lightnovelreader.api.book.BookVolumes
import io.nightfish.lightnovelreader.api.error.WebRequestError

@Immutable
data class BookshelfCardSnapshot(
    val bookInformation: BookInformation? = null,
    val lastUpdatedChapterTitle: String? = null,
    val loading: Boolean = true,
    val error: WebRequestError? = null,
)


internal fun Result<BookVolumes, WebRequestError>.lastChapterTitleOrNull(): String? =
    get()?.volumes?.lastOrNull()?.let { volume ->
        volume.chapters.lastOrNull()?.title?.let { title -> "${volume.volumeTitle} $title" }
    }