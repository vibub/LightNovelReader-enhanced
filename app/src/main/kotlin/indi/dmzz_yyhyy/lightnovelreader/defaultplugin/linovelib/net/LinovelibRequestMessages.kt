package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.content.res.Resources
import indi.dmzz_yyhyy.lightnovelreader.R

/** 网络异常保留诊断信息，只在展示边界解析用户文案，不将 URL 或日志原因透传到 UI。 */
internal fun Throwable.linovelibUserMessage(resources: Resources): String? {
    var error: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (error != null && visited.add(error)) {
        when (error) {
            is LinovelibBlockedException -> return when (error.reason) {
                LinovelibBlockedException.Reason.UnsupportedSite ->
                    resources.getString(R.string.linovelib_request_unsupported_site)
                LinovelibBlockedException.Reason.Redirect ->
                    resources.getString(R.string.linovelib_request_redirect_error)
                LinovelibBlockedException.Reason.Verification -> {
                    val waitMillis = error.retryAfterMillis
                    if (waitMillis != null && waitMillis > 0) {
                        resources.getString(R.string.linovelib_request_blocked_wait, retrySeconds(waitMillis))
                    } else {
                        resources.getString(R.string.linovelib_request_blocked)
                    }
                }
            }
            is LinovelibHttpException -> {
                val waitMillis = error.retryAfterMillis
                return if (error.statusCode == 429) {
                    if (waitMillis != null && waitMillis > 0) {
                        resources.getString(R.string.linovelib_request_rate_limited_wait, retrySeconds(waitMillis))
                    } else {
                        resources.getString(R.string.linovelib_request_rate_limited)
                    }
                } else if (waitMillis != null && waitMillis > 0) {
                    resources.getString(R.string.linovelib_request_http_wait, error.statusCode, retrySeconds(waitMillis))
                } else {
                    resources.getString(R.string.linovelib_request_http_error, error.statusCode)
                }
            }
        }
        error = error.cause
    }
    return null
}

private fun retrySeconds(millis: Long): Long =
    millis / 1_000 + if (millis % 1_000 > 0) 1 else 0
