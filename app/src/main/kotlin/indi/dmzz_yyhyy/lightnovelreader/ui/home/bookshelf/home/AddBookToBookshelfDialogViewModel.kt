package indi.dmzz_yyhyy.lightnovelreader.ui.home.bookshelf.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalBookDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddBookToBookshelfDialogViewModel @Inject constructor(
    private val bookshelfRepository: BookshelfRepository,
    private val localBookDataSource: LocalBookDataSource,
    private val webBookDataSourceProvider: WebBookDataSourceProvider
) : ViewModel() {
    var allBookshelfFlow = bookshelfRepository.getAllBookshelvesFlow()

    fun markSelectedBooks(selectedBookIds: List<String>, bookshelfIds: List<Int>) {
        CoroutineScope(Dispatchers.IO).launch {
            val sourceId = webBookDataSourceProvider.value.id.toLegacyCompatibleSourceId()
            selectedBookIds.forEach { bookId ->
                localBookDataSource.getBookInformation(sourceId, bookId)?.let { bookInformation ->
                    bookshelfIds.forEach {
                        bookshelfRepository.addBookIntoBookShelf(it,
                            bookInformation
                        )
                    }
                }
            }
        }
    }
}