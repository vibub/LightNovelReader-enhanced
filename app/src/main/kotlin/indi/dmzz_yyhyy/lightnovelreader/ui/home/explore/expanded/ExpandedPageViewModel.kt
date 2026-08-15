package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.expanded

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ManagedExploreExpandedPageDataSource
import indi.dmzz_yyhyy.lightnovelreader.data.text.TextProcessingRepository
import io.nightfish.lightnovelreader.api.web.explore.ExploreExpandedPageDataSource
import io.nightfish.lightnovelreader.api.web.explore.ExplorePageProvider
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExpandedPageViewModel @Inject constructor(
    private val exploreRepository: ExploreRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val textProcessingRepository: TextProcessingRepository,
    private val bookRepository: BookRepository
) : ViewModel() {
    private var expandedPageDataSource: ExploreExpandedPageDataSource? = null
    private var exploreExpandedPageBookListCollectJob: Job? = null
    private var pageMetadataJob: Job? = null
    private var lastExpandedPageDataSourceId: String = ""
    private val _uiState = MutableExpandedPageUiState()
    val uiState: ExpandedPageUiState = _uiState

    init {
        viewModelScope.launch {
            bookshelfRepository.getAllBookshelfBookIdsFlow().collect { ids ->
                _uiState.allBookshelfBookIds = ids.toList()
            }
        }
    }

    fun init(expandedPageDataSourceId: String) {
        if (exploreRepository.explorePageProvider !is ExplorePageProvider.DefaultExplorePageProvider) return
        val explorePageProvider = exploreRepository.explorePageProvider as ExplorePageProvider.DefaultExplorePageProvider
        if (expandedPageDataSourceId == lastExpandedPageDataSourceId) return
        lastExpandedPageDataSourceId = expandedPageDataSourceId

        exploreExpandedPageBookListCollectJob?.cancel()
        pageMetadataJob?.cancel()
        expandedPageDataSource = explorePageProvider
            .exploreExpandedPageDataSourceMap[expandedPageDataSourceId]
            ?.also { dataSource ->
                (dataSource as? ManagedExploreExpandedPageDataSource)?.reset()
            }

        _uiState.filters.clear()
        pageMetadataJob = viewModelScope.launch(Dispatchers.IO) {
            expandedPageDataSource?.let { dataSource ->
                _uiState.pageTitle = textProcessingRepository.processText { dataSource.title }
                if (dataSource is ManagedExploreExpandedPageDataSource) {
                    dataSource.filtersFlow.collect(::updateFilters)
                } else {
                    updateFilters(dataSource.filters)
                }
            }
        }
        loadBookResult(clearResults = true)
    }

    fun loadBookResult() {
        loadBookResult(clearResults = true)
    }

    fun retry() {
        loadBookResult(clearResults = false)
    }

    fun refreshCurrentResults() {
        (expandedPageDataSource as? ManagedExploreExpandedPageDataSource)
            ?.invalidateCurrentResultCache()
        loadBookResult(clearResults = true, refreshing = true)
    }

    fun loadMore() {
        val dataSource = expandedPageDataSource ?: return
        if (dataSource is ManagedExploreExpandedPageDataSource) {
            if (dataSource.requestLoadMore()) {
                _uiState.errorMessage = null
                _uiState.isLoadingMore = true
            }
        } else {
            dataSource.loadMore()
        }
    }

    fun clear() {
        lastExpandedPageDataSourceId = ""
        exploreExpandedPageBookListCollectJob?.cancel()
        pageMetadataJob?.cancel()
        expandedPageDataSource = null
    }

    private fun loadBookResult(
        clearResults: Boolean,
        refreshing: Boolean = false
    ) {
        if (clearResults) {
            _uiState.bookList.clear()
            _uiState.isEmptyResult = false
            _uiState.resultVersion++
        }
        _uiState.errorMessage = null
        _uiState.isInitialLoading = _uiState.bookList.isEmpty()
        _uiState.isLoadingMore = _uiState.bookList.isNotEmpty()
        _uiState.isRefreshing = refreshing

        exploreExpandedPageBookListCollectJob?.cancel()
        exploreExpandedPageBookListCollectJob = viewModelScope.launch(Dispatchers.IO) {
            expandedPageDataSource?.getResultFlow()?.collect { rawResult ->
                when (rawResult) {
                    is SearchResult.SingleBook -> addBook(rawResult.bookId)
                    is SearchResult.MultipleBook -> addBook(rawResult.bookId)
                    is SearchResult.Error -> {
                        _uiState.errorMessage = rawResult.error.message
                            ?.takeIf(String::isNotBlank)
                            ?: "加载失败"
                        finishLoading()
                    }
                    is SearchResult.Empty -> {
                        _uiState.isEmptyResult = _uiState.bookList.isEmpty()
                        finishLoading()
                    }
                    is SearchResult.End -> finishLoading()
                }
            }
        }
    }

    private fun addBook(bookId: String) {
        if (_uiState.bookList.none { it.first == bookId }) {
            _uiState.bookList.add(
                bookId to bookRepository.getBookInformationFlow(bookId)
            )
        }
        _uiState.isInitialLoading = false
        _uiState.isEmptyResult = false
    }

    private fun finishLoading() {
        _uiState.isInitialLoading = false
        _uiState.isLoadingMore = false
        _uiState.isRefreshing = false
    }

    private fun updateFilters(filters: List<Filter<*>>) {
        _uiState.filters.clear()
        _uiState.filters.addAll(filters)
    }
}
