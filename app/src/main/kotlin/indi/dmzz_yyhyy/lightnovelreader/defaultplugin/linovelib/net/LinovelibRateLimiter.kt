package indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.net

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

internal object LinovelibRateLimiter {
    private const val TAG = "LinovelibRateLimiter"
    private const val MIN_REQUEST_INTERVAL_MILLIS = 1_800L
    private const val REQUEST_JITTER_MILLIS = 700L
    private const val MAX_COOLDOWN_MILLIS = 5 * 60 * 1000L
    const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60 * 1000L
    const val CLOUDFLARE_COOLDOWN_MILLIS = 3 * 60 * 1000L

    private val semaphore = Semaphore(1)
    private val lock = Any()
    private var nextRequestAllowedAt = 0L
    private var cooldownUntil = 0L

    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit {
        waitForTurn()
        block()
    }

    fun coolDown(reason: String, delayMillis: Long) {
        val now = System.currentTimeMillis()
        val boundedDelay = delayMillis.coerceIn(0L, MAX_COOLDOWN_MILLIS)
        val nextCooldownUntil = now + boundedDelay
        val applied = synchronized(lock) {
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

    private suspend fun waitForTurn() {
        while (true) {
            val waitMillis = synchronized(lock) {
                val now = System.currentTimeMillis()
                val nextAllowedAt = maxOf(nextRequestAllowedAt, cooldownUntil)
                val wait = (nextAllowedAt - now).coerceAtLeast(0L)
                if (wait == 0L) {
                    nextRequestAllowedAt = now + MIN_REQUEST_INTERVAL_MILLIS + Random.nextLong(REQUEST_JITTER_MILLIS + 1)
                }
                wait
            }
            if (waitMillis == 0L) return
            Log.d(TAG, "delay ${waitMillis}ms before request")
            delay(waitMillis.milliseconds)
        }
    }
}
