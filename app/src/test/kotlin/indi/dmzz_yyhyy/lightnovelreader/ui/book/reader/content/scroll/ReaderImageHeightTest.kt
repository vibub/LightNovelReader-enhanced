package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import indi.dmzz_yyhyy.lightnovelreader.ui.components.scaledReaderImageHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderImageHeightTest {
    @Test
    fun imageHeightIsScaledToReaderWidth() {
        assertEquals(
            1920,
            scaledReaderImageHeightPx(
                targetWidthPx = 1080,
                sourceWidthPx = 720,
                sourceHeightPx = 1280
            )
        )
    }

    @Test
    fun invalidImageDimensionsAreRejected() {
        assertNull(
            scaledReaderImageHeightPx(
                targetWidthPx = 1080,
                sourceWidthPx = 0,
                sourceHeightPx = 1280
            )
        )
    }
}
