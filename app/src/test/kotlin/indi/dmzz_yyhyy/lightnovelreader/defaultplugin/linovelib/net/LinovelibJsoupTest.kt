package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

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
}
