package indi.dmzz_yyhyy.lightnovelreader.utils.network

import android.os.Build

object UserAgentGenerator {
    private const val APPLE_WEBKIT_VERSION = "537.36"
    private const val SAFARI_VERSION = "537.36"
    private const val CHROME_MAJOR_VERSION = 153

    private val desktopPlatforms = listOf(
        "Windows NT 10.0; Win64; x64",
        "Macintosh; Intel Mac OS X 10_15_7",
        "X11; Linux x86_64"
    )

    private val cachedUserAgent by lazy {
        buildUserAgent(
            androidVersion = Build.VERSION.RELEASE.orEmpty().ifBlank { "10" },
            model = Build.MODEL.orEmpty().ifBlank { "K" },
            chromeMajorVersion = CHROME_MAJOR_VERSION,
        )
    }

    private val cachedDesktopUserAgent by lazy {
        "Mozilla/5.0 (${desktopPlatforms.random()}) " +
                "AppleWebKit/$APPLE_WEBKIT_VERSION (KHTML, like Gecko) " +
                "Chrome/$CHROME_MAJOR_VERSION.0.0.0 Safari/$SAFARI_VERSION"
    }

    fun generate(): String = cachedUserAgent

    fun generateDesktop(): String = cachedDesktopUserAgent

    internal fun buildUserAgent(
        androidVersion: String,
        model: String,
        chromeMajorVersion: Int,
    ): String =
        "Mozilla/5.0 (Linux; Android $androidVersion; $model) " +
                "AppleWebKit/$APPLE_WEBKIT_VERSION (KHTML, like Gecko) " +
                "Chrome/$chromeMajorVersion.0.0.0 Mobile Safari/$SAFARI_VERSION"

}
