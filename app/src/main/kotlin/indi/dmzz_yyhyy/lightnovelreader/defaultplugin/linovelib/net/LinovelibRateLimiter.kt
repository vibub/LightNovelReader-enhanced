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
    const val DEFAULT_RATE_LIMIT_COOLDOWN_MILLIS = 60 * 1000L

    private val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val lock = Any()
    private var nextRequestAllowedAt = 0L
    private var cooldownUntil = 0L
    private var cooldownReason = ""
    private var cooldownIsBlocked = false
    private var requestIntervalMillis = INITIAL_REQUEST_INTERVAL_MILLIS
    private var successStreak = 0

    suspend fun <T> run(block: suspend () -> T): T {
        synchronized(lock) { throwIfCoolingDown(System.currentTimeMillis()) }
        return semaphore.withPermit {
            waitForTurn()
            try {
                block().also { recordSuccess() }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                recordFailure(throwable)
                throw throwable
            }
        }
    }

    fun coolDown(reason: String, delayMillis: Long, blocked: Boolean = false) {
        val now = System.currentTimeMillis()
        val boundedDelay = delayMillis.coerceAtLeast(0L)
        // Retry-After 是服务器指定的最早恢复时间，不能按客户端退避上限截短。
        val nextCooldownUntil = if (boundedDelay > Long.MAX_VALUE - now) {
            Long.MAX_VALUE
        } else {
            now + boundedDelay
        }
        val applied = synchronized(lock) {
            requestIntervalMillis = requestIntervalMillis
                .coerceAtLeast(INITIAL_REQUEST_INTERVAL_MILLIS)
                .coerceAtMost(MAX_REQUEST_INTERVAL_MILLIS)
            successStreak = 0
            if (nextCooldownUntil > cooldownUntil) {
                cooldownUntil = nextCooldownUntil
                cooldownReason = reason
                cooldownIsBlocked = blocked
                true
            } else {
                false
            }
        }
        if (applied && boundedDelay > 0) {
            Log.w(TAG, "cooldown ${boundedDelay}ms: $reason")
        }
    }

    /** WebView 主框架收到阻断响应时，共享原生请求的暂停时间。 */
    fun coolDown(durationMs: Long) {
        coolDown("WebView request blocked or rate limited", durationMs, blocked = true)
    }

    /** 只读查询；不会等待、清除或缩短已记录的冷却时间。 */
    fun remainingCooldownMillis(): Long = synchronized(lock) {
        (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)
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

    // 调用方持有 lock；冷却期间直接失败，不能让批量任务排队到期后自动重放。
    private fun throwIfCoolingDown(now: Long) {
        if (now >= cooldownUntil) return
        val remainingMillis = cooldownUntil - now
        val message = "Linovelib requests paused for ${remainingMillis}ms: $cooldownReason"
        if (cooldownIsBlocked) throw LinovelibBlockedException(message)
        throw LinovelibHttpException(429, message, remainingMillis)
    }

    private suspend fun waitForTurn() {
        while (true) {
            val waitMillis = synchronized(lock) {
                val now = System.currentTimeMillis()
                throwIfCoolingDown(now)
                val wait = (nextRequestAllowedAt - now).coerceAtLeast(0L)
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
