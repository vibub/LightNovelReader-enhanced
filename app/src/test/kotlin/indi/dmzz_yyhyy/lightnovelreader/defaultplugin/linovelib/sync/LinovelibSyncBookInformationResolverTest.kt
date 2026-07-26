package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class LinovelibSyncBookInformationResolverTest {
    @Test
    fun blankBookcaseTitleUsesStableFallback() {
        assertEquals("Linovelib 2734", resolveLinovelibSyncBookTitle("2734", ""))
    }

    @Test
    fun parsedBookcaseTitleIsKept() {
        assertEquals(
            "转生公主与天才千金的魔法革命",
            resolveLinovelibSyncBookTitle("2734", "转生公主与天才千金的魔法革命")
        )
    }
}
