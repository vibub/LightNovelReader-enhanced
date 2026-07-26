package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.account

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.Instant

class LinovelibAccountStore(
    private val userDataRepository: UserDataRepositoryApi
) {
    private val cookieData = userDataRepository.stringUserData(LinovelibConstants.COOKIE_PATH)
    private val lastSyncTimeData = userDataRepository.stringUserData(LinovelibConstants.LAST_SYNC_TIME_PATH)
    private val lastSyncErrorData = userDataRepository.stringUserData(LinovelibConstants.LAST_SYNC_ERROR_PATH)
    private val lastSyncSummaryData = userDataRepository.stringUserData(LinovelibConstants.LAST_SYNC_SUMMARY_PATH)

    @Volatile
    private var cachedCookie: String = ""

    fun getCookie(): String = cachedCookie

    suspend fun refreshCookie(): String = cookieData.getOrDefault("").trim().also {
        cachedCookie = it
    }

    fun getCookieFlow(): Flow<String> = cookieData.getFlowWithDefault("").onEach {
        cachedCookie = it.trim()
    }

    fun hasCookie(): Boolean = cachedCookie.isNotBlank()

    suspend fun hasStoredCookie(): Boolean = refreshCookie().isNotBlank()

    fun hasCookieFlow(): Flow<Boolean> = getCookieFlow().map { it.isNotBlank() }

    suspend fun saveCookie(cookie: String) {
        cachedCookie = cookie.trim()
        cookieData.set(cachedCookie)
        lastSyncTimeData.set(Instant.now().toString())
        lastSyncSummaryData.set("Cookie 已保存")
        lastSyncErrorData.set("")
    }

    suspend fun clearCookie() {
        cachedCookie = ""
        userDataRepository.remove(LinovelibConstants.COOKIE_PATH)
        userDataRepository.remove(LinovelibConstants.LAST_SYNC_TIME_PATH)
        userDataRepository.remove(LinovelibConstants.LAST_SYNC_ERROR_PATH)
        userDataRepository.remove(LinovelibConstants.LAST_SYNC_SUMMARY_PATH)
    }

    fun getLastSyncTimeFlow(): Flow<String> = lastSyncTimeData.getFlowWithDefault("")

    fun getLastSyncErrorFlow(): Flow<String> = lastSyncErrorData.getFlowWithDefault("")

    fun getLastSyncSummaryFlow(): Flow<String> = lastSyncSummaryData.getFlowWithDefault("")

    suspend fun markSyncSuccess(time: String, summary: String) {
        lastSyncTimeData.set(time)
        lastSyncSummaryData.set(summary)
        lastSyncErrorData.set("")
    }

    suspend fun markSyncError(message: String) {
        lastSyncErrorData.set(message)
    }
}
