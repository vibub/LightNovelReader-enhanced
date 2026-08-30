package indi.dmzz_yyhyy.lightnovelreader.data.download

import androidx.work.Constraints
import androidx.work.NetworkType
import indi.dmzz_yyhyy.lightnovelreader.data.userdata.UserDataRepository
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 下载任务使用的网络和存储策略。
 *
 * 新建或重新排队 WorkManager 任务时会读取当前设置；
 * Worker 仍会在每个章节开始前再次检查剩余空间。
 */
enum class DownloadNetworkPolicy(val key: String) {
    WIFI_ONLY("wifi_only"),
    ANY_NETWORK("any_network");

    companion object {
        fun fromKey(key: String?): DownloadNetworkPolicy = entries.firstOrNull { it.key == key }
            ?: WIFI_ONLY
    }
}

data class DownloadSettings(
    val networkPolicy: DownloadNetworkPolicy = DownloadNetworkPolicy.WIFI_ONLY,
    val onlyWhenCharging: Boolean = false,
    val minimumFreeStorageMb: Int = DEFAULT_MINIMUM_FREE_STORAGE_MB
) {
    val minimumFreeStorageBytes: Long
        get() = minimumFreeStorageMb.toLong() * BYTES_PER_MB

    /** 用于判断已排队任务是否已经应用当前下载设置。 */
    val constraintsKey: String
        get() = "${networkPolicy.key}:$onlyWhenCharging:$minimumFreeStorageMb"

    fun constraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            when (networkPolicy) {
                DownloadNetworkPolicy.WIFI_ONLY -> NetworkType.UNMETERED
                DownloadNetworkPolicy.ANY_NETWORK -> NetworkType.CONNECTED
            }
        )
        .setRequiresCharging(onlyWhenCharging)
        .setRequiresStorageNotLow(true)
        .build()

    companion object {
        const val DEFAULT_MINIMUM_FREE_STORAGE_MB = 256
        const val MINIMUM_FREE_STORAGE_MB = 0
        const val MAXIMUM_FREE_STORAGE_MB = 16 * 1024
        const val BYTES_PER_MB = 1024L * 1024L

        fun sanitizeMinimumFreeStorageMb(value: Int): Int = value.coerceIn(
            MINIMUM_FREE_STORAGE_MB,
            MAXIMUM_FREE_STORAGE_MB
        )
    }
}

@Singleton
class DownloadSettingsRepository @Inject constructor(
    userDataRepository: UserDataRepository
) {
    private val networkPolicy = userDataRepository.stringUserData(
        UserDataPath.Settings.Data.DownloadNetworkPolicy.path
    )
    private val onlyWhenCharging = userDataRepository.booleanUserData(
        UserDataPath.Settings.Data.DownloadOnlyWhenCharging.path
    )
    private val minimumFreeStorageMb = userDataRepository.stringUserData(
        UserDataPath.Settings.Data.DownloadMinimumFreeStorageMb.path
    )

    suspend fun get(): DownloadSettings = DownloadSettings(
        networkPolicy = DownloadNetworkPolicy.fromKey(networkPolicy.get()),
        onlyWhenCharging = onlyWhenCharging.getOrDefault(false),
        minimumFreeStorageMb = DownloadSettings.sanitizeMinimumFreeStorageMb(
            minimumFreeStorageMb.getOrDefault(
                DownloadSettings.DEFAULT_MINIMUM_FREE_STORAGE_MB.toString()
            ).toIntOrNull() ?: DownloadSettings.DEFAULT_MINIMUM_FREE_STORAGE_MB
        )
    )

    /** 监听下载设置，供已排队任务在设置变化后重建 WorkManager 约束。 */
    fun getFlow(): Flow<DownloadSettings> = combine(
        networkPolicy.getFlowWithDefault(DownloadNetworkPolicy.WIFI_ONLY.key),
        onlyWhenCharging.getFlowWithDefault(false),
        minimumFreeStorageMb.getFlowWithDefault(
            DownloadSettings.DEFAULT_MINIMUM_FREE_STORAGE_MB.toString()
        )
    ) { policy, charging, minimumStorageMb ->
        DownloadSettings(
            networkPolicy = DownloadNetworkPolicy.fromKey(policy),
            onlyWhenCharging = charging,
            minimumFreeStorageMb = DownloadSettings.sanitizeMinimumFreeStorageMb(
                minimumStorageMb.toIntOrNull()
                    ?: DownloadSettings.DEFAULT_MINIMUM_FREE_STORAGE_MB
            )
        )
    }.distinctUntilChanged()

    companion object {
        fun estimateRequiredBytes(
            chapterCount: Int,
            minimumFreeStorageBytes: Long,
            estimatedBytesPerChapter: Long = DEFAULT_ESTIMATED_BYTES_PER_CHAPTER
        ): Long = max(0, chapterCount).toLong()
            .coerceAtMost(Long.MAX_VALUE / max(1L, estimatedBytesPerChapter))
            .times(max(1L, estimatedBytesPerChapter))
            .let { estimatedBytes ->
                if (Long.MAX_VALUE - estimatedBytes < minimumFreeStorageBytes) {
                    Long.MAX_VALUE
                } else {
                    estimatedBytes + minimumFreeStorageBytes
                }
            }

        private const val DEFAULT_ESTIMATED_BYTES_PER_CHAPTER = 512L * 1024L
    }
}
