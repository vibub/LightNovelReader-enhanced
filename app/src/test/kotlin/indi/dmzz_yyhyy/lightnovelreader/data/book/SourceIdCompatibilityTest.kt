package indi.dmzz_yyhyy.lightnovelreader.data.book

import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.LinovelibConstants
import indi.dmzz_yyhyy.lightnovelreader.utils.ofId
import indi.dmzz_yyhyy.lightnovelreader.utils.toLegacyCompatibleSourceId
import io.nightfish.lightnovelreader.api.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceIdCompatibilityTest {
    @Test
    fun linovelibKeepsLegacyChapterCacheId() {
        assertEquals(
            LinovelibConstants.LEGACY_SOURCE_ID,
            LinovelibConstants.SOURCE_ID.toLegacyCompatibleSourceId()
        )
    }

    @Test
    fun wenku8KeepsLegacyChapterCacheId() {
        assertEquals(-791439186, "Wenku8".ofId().toLegacyCompatibleSourceId())
    }

    @Test
    fun externalSourceUsesIdentifierHashCode() {
        val sourceId = Identifier("example", "source")

        assertEquals(sourceId.hashCode(), sourceId.toLegacyCompatibleSourceId())
    }
}
