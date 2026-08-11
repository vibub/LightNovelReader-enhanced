package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class LinovelibJsoupTest {
    @Test
    fun defaultHeadersUseDesktopUserAgentForWebsiteRequests() {
        val userAgent = LinovelibJsoup().defaultHeaders()["User-Agent"].orEmpty()

        assertTrue(Regex("Windows NT|Macintosh|X11; Linux").containsMatchIn(userAgent))
        assertFalse(userAgent.contains("Mobile"))
    }

    @Test
    fun defaultHeadersCanUseMobileUserAgentForMobileWebsiteRequests() {
        val userAgent = LinovelibJsoup()
            .defaultHeaders(userAgentMode = LinovelibJsoup.UserAgentMode.Mobile)["User-Agent"]
            .orEmpty()

        assertTrue(userAgent.contains("Android"))
        assertTrue(userAgent.contains("Mobile"))
    }

    @Test
    fun sanitizeRequestCookieRemovesWebViewBoundCloudflareCookies() {
        assertEquals(
            "PHPSESSID=session; jieqiUserInfo=user",
            sanitizeLinovelibRequestCookie(
                "cf_clearance=clearance; PHPSESSID=session; __cf_bm=bot; " +
                    "cf_chl_2=challenge; _cfuvid=visitor; jieqiUserInfo=user"
            )
        )
    }

    @Test
    fun sanitizeRequestCookieDropsBlankAndMalformedEntries() {
        assertEquals("token=value", sanitizeLinovelibRequestCookie(" ; malformed; token=value; "))
    }

    @Test
    fun parseRetryAfterMillisSupportsSeconds() {
        assertEquals(60_000L, LinovelibJsoup.parseRetryAfterMillis("60", nowMillis = 0L))
    }

    @Test
    fun parseRetryAfterMillisSaturatesHugeSeconds() {
        assertEquals(Long.MAX_VALUE, LinovelibJsoup.parseRetryAfterMillis(Long.MAX_VALUE.toString(), nowMillis = 0L))
    }

    @Test
    fun parseRetryAfterMillisSupportsHttpDate() {
        val retryAt = ZonedDateTime.parse("Wed, 21 Oct 2015 07:28:00 GMT", DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant()
            .toEpochMilli()
        assertEquals(15_000L, LinovelibJsoup.parseRetryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", retryAt - 15_000L))
    }

    @Test
    fun parseRetryAfterMillisRejectsBlankOrInvalidValues() {
        assertNull(LinovelibJsoup.parseRetryAfterMillis(null))
        assertNull(LinovelibJsoup.parseRetryAfterMillis(""))
        assertNull(LinovelibJsoup.parseRetryAfterMillis("not-a-date"))
    }

    @Test
    fun isCloudflareBlockedDetectsStatusAndChallengeBody() {
        assertTrue(LinovelibJsoup.isCloudflareBlocked(403, ""))
        assertTrue(LinovelibJsoup.isCloudflareBlocked(503, ""))
        assertTrue(LinovelibJsoup.isCloudflareBlocked(200, "Cloudflare cf-chl just a moment"))
        assertTrue(LinovelibJsoup.isCloudflareBlocked(200, "请稍候，正在检查您的浏览器"))
        assertFalse(LinovelibJsoup.isCloudflareBlocked(200, "normal page"))
    }

    @Test
    fun normalizeCoverUrlResolvesRelativeBookCoverToDesktopHost() {
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/3/3080/3080s.jpg",
            LinovelibJsoup.normalizeCoverUrl("/files/article/image/3/3080/3080s.jpg")
        )
    }

    @Test
    fun normalizeCoverUrlKeepsDesktopBookCover() {
        val url = "${LinovelibConstants.BASE_URL}/files/article/image/3/3080/3080s.jpg"

        assertEquals(url, LinovelibJsoup.normalizeCoverUrl(url))
    }

    @Test
    fun normalizeCoverUrlRewritesMobileBookCoverToDesktopHost() {
        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/3/3080/3080l.jpg",
            LinovelibJsoup.normalizeCoverUrl(
                "${LinovelibConstants.MOBILE_BASE_URL}/files/article/image/3/3080/3080l.jpg"
            )
        )
    }

    @Test
    fun normalizeCoverUrlRewritesSameRootSubdomainAndKeepsSuffix() {
        val mobileHost = LinovelibConstants.MOBILE_BASE_URL.substringAfter("://")
        val rootDomain = mobileHost.substringAfter('.')

        assertEquals(
            "${LinovelibConstants.BASE_URL}/files/article/image/3/3080/3080l.jpg?v=2#cover",
            LinovelibJsoup.normalizeCoverUrl(
                "https://legacy.$rootDomain/files/article/image/3/3080/3080l.jpg?v=2#cover"
            )
        )
    }

    @Test
    fun normalizeCoverUrlKeepsThirdPartyCdn() {
        val url = "https://img3.readpai.com/cover/3080/309000.jpg"

        assertEquals(url, LinovelibJsoup.normalizeCoverUrl(url))
    }

    @Test
    fun normalizeCoverUrlDoesNotRewriteOtherSitePaths() {
        val url = "${LinovelibConstants.MOBILE_BASE_URL}/themes/zhmb/images/book-cover-no.svg"

        assertEquals(url, LinovelibJsoup.normalizeCoverUrl(url))
    }

    @Test
    fun normalizeCoverUrlRejectsUnsupportedUrlsAndKeepsMalformedAbsoluteUrl() {
        assertEquals("", LinovelibJsoup.normalizeCoverUrl("data:image/png;base64,abc"))
        assertEquals("https://[", LinovelibJsoup.normalizeCoverUrl("https://["))
    }
}
