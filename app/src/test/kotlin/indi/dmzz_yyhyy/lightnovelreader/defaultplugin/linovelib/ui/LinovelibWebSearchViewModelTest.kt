package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinovelibWebSearchViewModelTest {
    @Test
    fun firstUrlIsAllowedForBookDetection() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/123.html"))
    }

    @Test
    fun repeatedUrlIsNotAllowedForBookDetection() {
        val viewModel = LinovelibWebSearchViewModel()
        val url = "https://m.bilinovel.com/novel/123.html"

        assertTrue(viewModel.shouldDetectBookAtUrl(url))
        assertFalse(viewModel.shouldDetectBookAtUrl(url))
    }

    @Test
    fun returningToUrlAfterAnotherUrlIsAllowedForBookDetection() {
        val viewModel = LinovelibWebSearchViewModel()
        val detailUrl = "https://m.bilinovel.com/novel/123.html"

        assertTrue(viewModel.shouldDetectBookAtUrl(detailUrl))
        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/search.php?q=test"))
        assertTrue(viewModel.shouldDetectBookAtUrl(detailUrl))
    }

    @Test
    fun switchingBetweenBookUrlsAllowsEachForBookDetection() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/123.html"))
        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/456.html"))
    }

    @Test
    fun backNavigationSuppressesChangedUrlUntilFinished() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/123.html"))
        viewModel.beginBackNavigation()
        assertFalse(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/456.html"))
        viewModel.finishBackNavigation()
        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/789.html"))
    }

    @Test
    fun repeatedCurrentUrlDoesNotEndBackNavigation() {
        val viewModel = LinovelibWebSearchViewModel()
        val currentUrl = "https://m.bilinovel.com/novel/123.html"

        assertTrue(viewModel.shouldDetectBookAtUrl(currentUrl))
        viewModel.beginBackNavigation()
        assertFalse(viewModel.shouldDetectBookAtUrl(currentUrl))
        assertFalse(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/456.html"))
    }

    @Test
    fun backNavigationSuppressesMultipleChangedUrlsUntilFinished() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/123.html"))
        viewModel.beginBackNavigation()
        assertFalse(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/456.html"))
        assertFalse(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/789.html"))
        viewModel.finishBackNavigation()
        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/1011.html"))
    }

    @Test
    fun forwardAfterBackNavigationCanDetectPreviousDetailAgain() {
        val viewModel = LinovelibWebSearchViewModel()
        val detailUrl = "https://m.bilinovel.com/novel/123.html"
        val searchUrl = "https://m.bilinovel.com/search.php?q=test"

        assertTrue(viewModel.shouldDetectBookAtUrl(detailUrl))
        viewModel.beginBackNavigation()
        assertFalse(viewModel.shouldDetectBookAtUrl(searchUrl))
        viewModel.finishBackNavigation()
        assertTrue(viewModel.shouldDetectBookAtUrl(detailUrl))
    }

    @Test
    fun finishingBackNavigationWithoutUrlChangeRestoresDetection() {
        val viewModel = LinovelibWebSearchViewModel()

        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/123.html"))
        viewModel.beginBackNavigation()
        viewModel.finishBackNavigation()
        assertTrue(viewModel.shouldDetectBookAtUrl("https://m.bilinovel.com/novel/456.html"))
    }
}
