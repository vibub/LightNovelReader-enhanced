package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibWebSearchViewModelTest {
    @Test
    fun firstUrlIsTreatedAsChanged() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.hasUrlChanged("https://m.bilinovel.com/novel/123.html"))
    }

    @Test
    fun repeatedUrlIsNotTreatedAsChanged() {
        val viewModel = LinovelibWebSearchViewModel()
        val url = "https://m.bilinovel.com/novel/123.html"

        assertTrue(viewModel.hasUrlChanged(url))
        assertFalse(viewModel.hasUrlChanged(url))
    }

    @Test
    fun returningToUrlAfterAnotherUrlIsTreatedAsChanged() {
        val viewModel = LinovelibWebSearchViewModel()
        val detailUrl = "https://m.bilinovel.com/novel/123.html"

        assertTrue(viewModel.hasUrlChanged(detailUrl))
        assertTrue(viewModel.hasUrlChanged("https://m.bilinovel.com/search.php?q=test"))
        assertTrue(viewModel.hasUrlChanged(detailUrl))
    }

    @Test
    fun switchingBetweenBookUrlsTreatsEachUrlAsChanged() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.hasUrlChanged("https://m.bilinovel.com/novel/123.html"))
        assertTrue(viewModel.hasUrlChanged("https://m.bilinovel.com/novel/456.html"))
    }
}
