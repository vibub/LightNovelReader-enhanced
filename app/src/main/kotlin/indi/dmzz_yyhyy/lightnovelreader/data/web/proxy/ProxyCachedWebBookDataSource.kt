package indi.dmzz_yyhyy.lightnovelreader.data.web.proxy

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.onOk
import io.nightfish.lightnovelreader.api.error.WebRequestError
import io.nightfish.lightnovelreader.api.web.WebDataSourcePriority

class ProxyCachedWebBookDataSource(
    override val proxiedWebBookDataSource: ProxyWebBookDataSource
) : ProxyWebBookDataSource {
    private fun requestKey(method: String, vararg values: String): String = buildString {
        append(method)
        values.forEach { value ->
            append(':')
            append(value.length)
            append(':')
            append(value)
        }
    }

    private inline fun <reified T : Any> getOrCache(
        key: String,
        block: () -> Result<T, WebRequestError>
    ): Result<T, WebRequestError> {
        val cacheKey = key.hashCode()
        val value = origin.cache?.getCache<T>(cacheKey) ?: return block.invoke()
            .onOk {
                origin.cache?.cache(cacheKey, it)
            }
        return Ok(value)
    }

    override suspend fun getBookInformation(id: String, priority: WebDataSourcePriority) =
        getOrCache(requestKey("book_information", id)) {
            proxiedWebBookDataSource.getBookInformation(id, priority)
        }

    override suspend fun getBookVolumes(id: String, priority: WebDataSourcePriority) =
        getOrCache(requestKey("book_volumes", id)) {
            proxiedWebBookDataSource.getBookVolumes(id, priority)
        }

    override suspend fun getChapterContent(
        chapterId: String,
        bookId: String,
        priority: WebDataSourcePriority
    ) = getOrCache(requestKey("chapter_content", chapterId, bookId)) {
        proxiedWebBookDataSource.getChapterContent(chapterId, bookId, priority)
    }
}
