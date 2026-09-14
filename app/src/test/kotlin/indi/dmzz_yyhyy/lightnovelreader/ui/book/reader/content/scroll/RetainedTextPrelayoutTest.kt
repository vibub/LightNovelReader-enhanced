package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class RetainedTextPrelayoutTest {
    private class Harness(scope: CoroutineScope, ignoreCancellation: Boolean = false) {
        val windows = Channel<List<String>>(Channel.UNLIMITED)
        val retained = Channel<Set<String>>(Channel.UNLIMITED)
        val started = Channel<String>(Channel.UNLIMITED)
        val published = Channel<String>(Channel.UNLIMITED)
        val gates = mutableMapOf<String, CompletableDeferred<Any?>>()
        val calls = mutableMapOf<String, Int>()
        val finished = mutableSetOf<String>()
        val cache = mutableMapOf<String, Any>()
        val job = scope.launch {
            windows.receiveAsFlow().preloadRetainedText(
                retain = { keys ->
                    cache.keys.retainAll(keys)
                    retained.trySend(keys)
                },
                isPrepared = cache::containsKey,
                prepare = { key ->
                    calls[key] = (calls[key] ?: 0) + 1
                    val gate = CompletableDeferred<Any?>()
                    gates[key] = gate
                    started.trySend(key)
                    try {
                        if (ignoreCancellation) withContext(NonCancellable) { gate.await() }
                        else gate.await()
                    } finally {
                        finished += key
                    }
                },
                publish = { key, value ->
                    cache[key] = value
                    published.trySend(key)
                }
            )
        }

        suspend fun window(vararg keys: String) {
            windows.send(keys.toList())
            assertEquals(keys.toSet(), retained.receive())
            yield()
        }

        suspend fun finish() {
            windows.close()
            job.join()
        }
    }

    @Test
    fun chapterShiftKeepsOverlappingWork() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this)
            try {
                h.window("previous", "next")
                assertEquals(setOf("previous", "next"), setOf(h.started.receive(), h.started.receive()))
                val overlapping = h.gates.getValue("next")
                h.window("next", "following")
                assertEquals("following", h.started.receive())
                assertSame(overlapping, h.gates.getValue("next"))
                assertEquals(1, h.calls["next"])
                assertTrue("离开窗口的排版应取消", "previous" in h.finished)
                val result = Any()
                overlapping.complete(result)
                h.gates.getValue("following").complete(Any())
                h.finish()
                assertSame(result, h.cache["next"])
                assertFalse(h.cache.containsKey("previous"))
            } finally {
                h.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun reorderingAndDuplicateKeysDoNotRestartWork() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this)
            try {
                h.window("a", "b")
                h.started.receive()
                h.started.receive()
                h.window("b", "a", "b")
                assertEquals(mapOf("a" to 1, "b" to 1), h.calls)
                h.gates.values.forEach { it.complete(Any()) }
                h.published.receive()
                h.published.receive()
                h.window("a", "b")
                assertEquals(mapOf("a" to 1, "b" to 1), h.calls)
                h.finish()
            } finally {
                h.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun foregroundResultIsNotOverwritten() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this)
            try {
                h.window("a")
                h.started.receive()
                val foreground = Any()
                h.cache["a"] = foreground
                h.gates.getValue("a").complete(Any())
                h.finish()
                assertSame(foreground, h.cache["a"])
                assertTrue(h.published.tryReceive().isFailure)
            } finally {
                h.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun removedWorkCannotPublishEvenIfPreparationIgnoresCancellation() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this, ignoreCancellation = true)
            try {
                h.window("a")
                h.started.receive()
                h.window()
                h.gates.getValue("a").complete(Any())
                h.finish()
                assertTrue(h.cache.isEmpty())
                assertTrue(h.published.tryReceive().isFailure)
            } finally {
                h.gates.values.forEach { it.complete(null) }
                h.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun failedPreparationCanRetryOnNextWindowUpdate() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this)
            try {
                h.window("a")
                h.started.receive()
                h.gates.getValue("a").complete(null)
                yield()
                h.window("a")
                assertEquals("a", h.started.receive())
                assertEquals(2, h.calls["a"])
                h.gates.getValue("a").complete(Any())
                h.finish()
                assertTrue(h.cache.containsKey("a"))
            } finally {
                h.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun leavingReaderCancelsAllPendingWork() = runBlocking {
        withTimeout(5000.milliseconds) {
            val h = Harness(this)
            h.window("a", "b")
            h.started.receive()
            h.started.receive()
            h.job.cancelAndJoin()
            assertEquals(setOf("a", "b"), h.finished)
            assertTrue(h.cache.isEmpty())
        }
    }
}
