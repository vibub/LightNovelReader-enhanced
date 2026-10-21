package indi.dmzz_yyhyy.lightnovelreader.utils.network

import android.os.Build

object UserAgentGenerator {
    private const val APPLE_WEBKIT_VERSION = "537.36"
    private const val SAFARI_VERSION = "537.36"
    private const val CHROME_MAJOR_VERSION = 153

    private val cachedUserAgent by lazy {
        buildUserAgent(
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "10" },
            model = Build.MODEL.orEmpty().ifBlank { "K" },
            chromeMajorVersion = CHROME_MAJOR_VERSION,
        )
    }

    fun generate(): String = cachedUserAgent

    // 桌面模式保留设备实际网页内核版本，平台不再随进程随机变化。
    fun generateDesktop(
        webViewUserAgent: String = "Mozilla/5.0 (X11; Linux x86_64) " +
            "AppleWebKit/$APPLE_WEBKIT_VERSION (KHTML, like Gecko) " +
            "Chrome/$CHROME_MAJOR_VERSION.0.0.0 Safari/$SAFARI_VERSION"
    ): String = webViewUserAgent
        .replaceFirst(Regex("\\([^)]*\\)"), "(X11; Linux x86_64)")
        .replace("Version/4.0 ", "")
        .replace(" Mobile ", " ")

    internal fun buildUserAgent(
        androidVersion: String,
        model: String,
        chromeMajorVersion: Int,
    ): String =
        "Mozilla/5.0 (Linux; Android $androidVersion; $model) " +
                "AppleWebKit/$APPLE_WEBKIT_VERSION (KHTML, like Gecko) " +
                "Chrome/$chromeMajorVersion.0.0.0 Mobile Safari/$SAFARI_VERSION"

}
