package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibChapterCommentRepositoryTest {
    @Test
    fun hotRequestUsesBaseChapterIdAndLastPageReferer() {
        val request = buildLinovelibCommentRequest(
            bookId = "3095.html",
            chapterId = "154931_5.html",
            refererChapterPageId = "154931_5",
            query = LinovelibCommentQuery.Hot,
            pageIndex = 9,
            hasCookie = false
        )

        assertEquals("${LinovelibConstants.BASE_URL}/comment/php/api.php?action=get_list", request.url)
        assertEquals(LinovelibConstants.chapterUrl("3095", "154931_5"), request.referer)
        assertFalse(request.useCookie)
        assertEquals(
            linkedMapOf(
                "cmtid" to "154931",
                "catid" to "3095",
                "pageSize" to "5",
                "query" to "hot"
            ),
            request.formData
        )
    }

    @Test
    fun allRequestUsesCookiePageSizeAndPageIndex() {
        val request = buildLinovelibCommentRequest(
            bookId = "8",
            chapterId = "1843_5",
            refererChapterPageId = "",
            query = LinovelibCommentQuery.All,
            pageIndex = 3,
            hasCookie = true
        )

        assertTrue(request.useCookie)
        assertEquals(LinovelibConstants.chapterUrl("8", "1843"), request.referer)
        assertEquals(
            linkedMapOf(
                "cmtid" to "1843",
                "catid" to "8",
                "pageSize" to "20",
                "pageIndex" to "3",
                "query" to "all"
            ),
            request.formData
        )
    }

    @Test
    fun guestCannotBuildAllCommentsRequest() {
        assertThrows(LinovelibCommentLoginRequiredException::class.java) {
            buildLinovelibCommentRequest(
                bookId = "8",
                chapterId = "1843",
                refererChapterPageId = "1843_5",
                query = LinovelibCommentQuery.All,
                pageIndex = 1,
                hasCookie = false
            )
        }
    }

    @Test
    fun allRequestCoercesPageIndexToOne() {
        val request = buildLinovelibCommentRequest(
            bookId = "8",
            chapterId = "1843",
            refererChapterPageId = "1843_5",
            query = LinovelibCommentQuery.All,
            pageIndex = 0,
            hasCookie = true
        )

        assertEquals("1", request.formData["pageIndex"])
    }

    @Test
    fun loginRequiredMessageDetectionDoesNotTreatNetworkErrorsAsLoginFailures() {
        assertTrue(isLinovelibCommentLoginRequiredMessage("请先登录后查看全部评论"))
        assertTrue(isLinovelibCommentLoginRequiredMessage("Cookie 可能已失效，请重新登入"))
        assertFalse(isLinovelibCommentLoginRequiredMessage("请求被 Cloudflare 拦截"))
        assertFalse(isLinovelibCommentLoginRequiredMessage("读取评论失败"))
    }
}
