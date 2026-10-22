package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.utils.EncodedImageHttpException
import java.net.URI

internal object LinovelibImageRequestPolicy {
    private val hosts = setOf(
        URI(LinovelibConstants.BASE_URL).host,
        URI(LinovelibConstants.MOBILE_BASE_URL).host,
        URI(LinovelibConstants.FILTER_BASE_URL).host
    )

    suspend fun <T> run(url: String, request: () -> T): T {
        val host = runCatching { URI(url).host }.getOrNull()
        if (host !in hosts) return request()
        return LinovelibRateLimiter.run {
            try {
                request()
            } catch (error: EncodedImageHttpException) {
                val retryAfter = LinovelibJsoup.parseRetryAfterMillis(error.retryAfter)
                if (error.statusCode in listOf(403, 429, 503)) {
                    val delay = if (error.statusCode == 429) {
                        retryAfter ?: LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS
                    } else {
                        maxOf(retryAfter ?: 0L, LinovelibRateLimiter.DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS)
                    }
                    LinovelibRateLimiter.coolDown(
                        diagnosticReason = "Image HTTP ${error.statusCode}: $host",
                        delayMillis = delay,
                        blocked = error.statusCode != 429
                    )
                } else if (error.statusCode >= 500 && retryAfter != null) {
                    LinovelibRateLimiter.coolDown("Image HTTP ${error.statusCode}: $host", retryAfter)
                }
                throw error
            }
        }
    }
}
