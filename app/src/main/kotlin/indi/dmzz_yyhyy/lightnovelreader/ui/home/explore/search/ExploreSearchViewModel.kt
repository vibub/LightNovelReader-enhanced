package indi.dmzz_yyhyy.lightnovelreader.ui.home.explore.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.Ok
import dagger.hilt.android.lifecycle.HiltViewModel
import indi.dmzz_yyhyy.lightnovelreader.data.book.BookRepository
import indi.dmzz_yyhyy.lightnovelreader.data.bookshelf.BookshelfRepository
import indi.dmzz_yyhyy.lightnovelreader.data.explore.ExploreRepository
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibSearchProvider
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.search.LinovelibWebSearchRequest
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.util.LocalString
import io.nightfish.lightnovelreader.api.web.search.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ExploreSearchViewModel @Inject constructor(
    private val exploreRepository: ExploreRepository,
    private val bookshelfRepository: BookshelfRepository,
    private val bookRepository: BookRepository,
    userDataRepository: UserDataRepository
) : ViewModel() {
    private val _uiState = MutableExploreSearchUiState()
    private val searchHistoryUserData =
        userDataRepository.stringListUserData(UserDataPath.Search.History.path)
    private var searchTypeTipMap = mutableMapOf<String, LocalString>()
    private var searchJob: Job? = null
    private var nextLinovelibRequestId = 0L
    private var initialized = false
    val uiState: ExploreSearchUiState = _uiState

    override fun onCleared() {
        searchJob?.cancel()
    }

    fun init() {
        searchTypeTipMap.clear()
        _uiState.searchTypeNameMap.clear()
        _uiState.searchTypeIdList.clear()
        for (type in exploreRepository.searchTypes) {
            searchTypeTipMap[type.type] = type.tip
            _uiState.searchTypeNameMap[type.type] = type.name
            _uiState.searchTypeIdList.add(type.type)
        }
        if (_uiState.searchType !in _uiState.searchTypeIdList) {
            _uiState.searchType = _uiState.searchTypeIdList.firstOrNull().orEmpty()
        }
        _uiState.searchTip = searchTypeTipMap.getOrDefault(_uiState.searchType, LocalString(""))
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            searchHistoryUserData.getFlow().collect {
                it?.let {
                    _uiState.historyList = it.reversed()
                }
            }
        }
        viewModelScope.launch {
            bookshelfRepository.getAllBookshelfBookIdsFlow().collect {
                _uiState.allBookshelfBookIds = it.toMutableList()
            }
        }
    }

    fun changeSearchType(searchTypeId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.searchType = searchTypeId
            _uiState.searchTip = searchTypeTipMap.getOrDefault(_uiState.searchType, LocalString(""))
        }
    }

    fun deleteHistory(history: String) {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryUserData.update {
                val newList = it.toMutableList()
                newList.remove(history)
                return@update newList
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryUserData.update { emptyList() }
        }
    }

    fun search(
        keyword: String,
        navigateToSingleBook: (bookId: String) -> Unit,
        openLinovelibWebSearch: (keyword: String) -> Unit
    ) {
        val trimmedKeyword = keyword.trim()
        searchJob?.cancel()
        _uiState.linovelibSearchRequest = null
        _uiState.linovelibSearchKeyword = null
        _uiState.isLoading = true
        _uiState.isLoadingComplete = false
        _uiState.errorMessage = ""
        _uiState.searchResult.clear()
        if (trimmedKeyword.isBlank()) {
            _uiState.isLoading = false
            _uiState.isLoadingComplete = true
            return
        }
        if (exploreRepository.currentSourceId == LinovelibConstants.SOURCE_ID) {
            _uiState.linovelibSearchKeyword = trimmedKeyword
            _uiState.linovelibSearchRequest = LinovelibWebSearchRequest(++nextLinovelibRequestId, trimmedKeyword)
            _uiState.isLoading = false
            saveSearchHistory(trimmedKeyword)
            return
        }
        val searchType = exploreRepository.searchTypes.firstOrNull { it.type == _uiState.searchType }
        if (searchType == null) {
            _uiState.isLoading = false
            _uiState.isLoadingComplete = true
            return
        }
        searchJob = viewModelScope.launch {
            val flow = exploreRepository.search(searchType, keyword)
            _uiState.isLoading = false
            flow.collect {
                when (it) {
                    is SearchResult.SingleBook -> launch(Dispatchers.Main) {
                        _uiState.searchBarExpanded = true
                        navigateToSingleBook(it.bookId)
                    }

                    is SearchResult.MultipleBook -> _uiState.searchResult.add(
                        it.bookId to bookRepository.getBookInformationFlow(
                            it.bookId
                        )
                    )

                    is SearchResult.Error -> {
                        _uiState.isLoadingComplete = true
                        _uiState.errorMessage = it.error.message.toString()
                        if (it.error.message == LinovelibConstants.SEARCH_BLOCKED_MESSAGE) {
                            launch(Dispatchers.Main) {
                                openLinovelibWebSearch(keyword)
                            }
                        }
                    }

                    is SearchResult.End -> _uiState.isLoadingComplete = true
                    is SearchResult.Empty -> {
                        _uiState.isLoadingComplete = true
                    }
                }
            }
        }
        saveSearchHistory(keyword)
    }

    suspend fun acceptLinovelibSearchPage(requestId: Long, url: String, html: String): Boolean {
        val request = _uiState.linovelibSearchRequest?.takeIf { it.id == requestId } ?: return false
        if (exploreRepository.currentSourceId != LinovelibConstants.SOURCE_ID) return false
        val provider = exploreRepository.searchProvider as? LinovelibSearchProvider ?: return false
        val books = withContext(Dispatchers.Default) {
            provider.parseWebSearchPage(request, url, html)
        } ?: return false
        if (_uiState.linovelibSearchRequest != request ||
            exploreRepository.currentSourceId != LinovelibConstants.SOURCE_ID
        ) return false
        // 直接使用网页摘要，避免每个搜索卡片再请求一次详情，也不覆盖完整详情缓存。
        _uiState.searchResult.clear()
        books.forEach { book -> _uiState.searchResult.add(book.id to flowOf(Ok(book))) }
        _uiState.searchBarExpanded = false
        _uiState.isLoading = false
        _uiState.isLoadingComplete = true
        _uiState.linovelibSearchRequest = null
        return true
    }

    fun updateLinovelibSearchState(requestId: Long, errorMessage: String?) {
        if (_uiState.linovelibSearchRequest?.id != requestId ||
            exploreRepository.currentSourceId != LinovelibConstants.SOURCE_ID
        ) return
        _uiState.errorMessage = errorMessage.orEmpty()
        _uiState.isLoadingComplete = errorMessage != null
        if (errorMessage != null) _uiState.searchBarExpanded = false
    }

    fun cancelLinovelibSearch(requestId: Long) {
        if (_uiState.linovelibSearchRequest?.id != requestId) return
        _uiState.linovelibSearchRequest = null
        _uiState.isLoading = false
        _uiState.isLoadingComplete = true
        _uiState.errorMessage = "已取消搜索，可重新搜索或使用网页搜索。"
    }

    fun acceptLinovelibSearchBook(requestId: Long, bookId: String): Boolean {
        if (_uiState.linovelibSearchRequest?.id != requestId ||
            exploreRepository.currentSourceId != LinovelibConstants.SOURCE_ID
        ) return false
        _uiState.linovelibSearchRequest = null
        _uiState.searchResult.add(bookId to bookRepository.getBookInformationFlow(bookId))
        _uiState.isLoading = false
        _uiState.isLoadingComplete = true
        return true
    }

    private fun saveSearchHistory(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            searchHistoryUserData.update {
                val newList = it.toMutableList()
                if (it.contains(keyword))
                    newList.remove(keyword)
                newList.add(keyword)
                return@update newList
            }
        }
    }

    fun updateSuggestions(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.suggestions = exploreRepository.getSuggestions(_uiState.historyList, keyword)
        }
    }
}
