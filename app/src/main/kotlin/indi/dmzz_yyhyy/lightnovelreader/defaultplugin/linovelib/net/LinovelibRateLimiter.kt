package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal object LinovelibRateLimiter {
    private const val TAG = "LinovelibRateLimiter"
    private const val MAX_CONCURRENT_REQUESTS = 2
    private const val MIN_REQUEST_INTERVAL_MILLIS = 900L
    private const val INITIAL_REQUEST_INTERVAL_MILLIS = 1_200L
    private const val MAX_REQUEST_INTERVAL_MILLIS = 2_400L
    private const val REQUEST_JITTER_MILLIS = 350L
    private const val SUCCESS_STREAK_TO_SPEED_UP = 6
    private const val MAX_COOLDOWN_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60 * 1000L

    private val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val lock = Any()
    private var nextRequestAllowedAt = 0L
    private var cooldownUntil = 0L
    private var requestIntervalMillis = INITIAL_REQUEST_INTERVAL_MILLIS
    private var successStreak = 0

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit {
        waitForTurn()
        try {
            block().also { recordSuccess() }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            recordFailure(throwable)
            throw throwable
        }
    }

    fun coolDown(reason: String, delayMillis: Long) {
        val now = System.currentTimeMillis()
        val boundedDelay = delayMillis.coerceIn(0L, MAX_COOLDOWN_MILLIS)
        val nextCooldownUntil = now + boundedDelay
        val applied = synchronized(lock) {
            requestIntervalMillis = requestIntervalMillis
                .coerceAtLeast(INITIAL_REQUEST_INTERVAL_MILLIS)
                .coerceAtMost(MAX_REQUEST_INTERVAL_MILLIS)
            successStreak = 0
            if (nextCooldownUntil > cooldownUntil) {
                cooldownUntil = nextCooldownUntil
                true
            } else {
                false
            }
        }
        if (applied && boundedDelay > 0) {
            Log.w(TAG, "cooldown ${boundedDelay}ms: $reason")
        }
    }

    private fun recordSuccess() {
        synchronized(lock) {
            successStreak++
            if (successStreak >= SUCCESS_STREAK_TO_SPEED_UP) {
                requestIntervalMillis = (requestIntervalMillis - 100L)
                    .coerceAtLeast(MIN_REQUEST_INTERVAL_MILLIS)
                successStreak = 0
            }
        }
    }

    private fun recordFailure(throwable: Throwable) {
        val increase = when (throwable) {
            is LinovelibHttpException -> when {
                throwable.statusCode == 429 -> 700L
                throwable.statusCode == 403 || throwable.statusCode == 503 -> 500L
                else -> 300L
            }
            else -> 250L
        }
        synchronized(lock) {
            successStreak = 0
            requestIntervalMillis = (requestIntervalMillis + increase)
                .coerceAtMost(MAX_REQUEST_INTERVAL_MILLIS)
        }
    }

    private suspend fun waitForTurn() {
        while (true) {
            val waitMillis = synchronized(lock) {
                val now = System.currentTimeMillis()
                val nextAllowedAt = maxOf(nextRequestAllowedAt, cooldownUntil)
                val wait = (nextAllowedAt - now).coerceAtLeast(0L)
                if (wait == 0L) {
                    nextRequestAllowedAt = now + requestIntervalMillis +
                        Random.nextLong(REQUEST_JITTER_MILLIS + 1)
                }
                wait
            }
            if (waitMillis == 0L) return
            Log.d(TAG, "delay ${waitMillis}ms before request")
            delay(waitMillis.milliseconds)
        }
    }
}
