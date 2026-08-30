package indi.dmzz_yyhyy.lightnovelreader.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class ExtentionsTest {
    @Test
    fun throttleLatestContinuesEmittingFromLongLivedFlow() = runBlocking {
        val values = flow {
            emit(1)
            delay(100.milliseconds)
            emit(2)
            delay(100.milliseconds)
            emit(3)
        }.throttleLatest(10).toList()

        assertEquals(listOf(1, 2, 3), values)
    }
}
