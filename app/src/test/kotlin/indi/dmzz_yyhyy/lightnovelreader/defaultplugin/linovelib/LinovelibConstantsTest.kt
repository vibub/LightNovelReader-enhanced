package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibConstantsTest {
    @Test
    fun extractBookIdFromMobileDetailUrl() {
        assertEquals(
            "123",
            LinovelibConstants.extractBookIdFromUrl("https://m.bilinovel.com/novel/123.html")
        )
    }

    @Test
    fun extractBookIdFromDesktopDetailUrl() {
        assertEquals(
            "123",
            LinovelibConstants.extractBookIdFromUrl("https://www.linovelib.com/novel/123.html")
        )
    }

    @Test
    fun extractBookIdFromBilinovelNetDetailUrl() {
        assertEquals(
            "123",
            LinovelibConstants.extractBookIdFromUrl("https://www.bilinovel.net/novel/123.html")
        )
    }

    @Test
    fun extractBookIdRejectsUnrelatedHost() {
        assertNull(LinovelibConstants.extractBookIdFromUrl("https://example.com/novel/4359.html"))
    }
}
