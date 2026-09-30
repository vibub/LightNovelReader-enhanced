package indi.dmzz_yyhyy.lightnovelreader.data.local.room.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
@Entity(tableName = "user_data")
data class UserDataEntity(
    @PrimaryKey
    val path: String,
    val group: String,
    val type: String,
    val value: String
) : Mergeable<UserDataEntity> {
    override fun merge(new: UserDataEntity): UserDataEntity {
        return when (type) {
            "StringList" -> copy(
                value = (value.split(",") + new.value.split(","))
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(",")
            )

            else -> new
        }
    }

    companion object {
        /** DB 升级与旧备份导入共用；只转换内置路径，不改写数据源键或锚点中的书籍/章节 ID。 */
        internal fun migrateLegacyPaths(
            entities: List<UserDataEntity>,
            preserveLegacyDefaults: Boolean = false
        ): List<UserDataEntity> {
            val readerKeys = setOf(
                "fontSize", "fontWeigh", "keepScreenOn", "enableHideStatusBar",
                "enableBackgroundImage", "backgroundImageDisplayMode", "isUsingFlipPage",
                "isUsingClickFlipPage", "isUsingContinuousScrolling", "isUsingVolumeKeyFlip",
                "volumeKeyContinuousFlipInterval", "flipAnime", "fastChapterChange",
                "batteryIndicatorDisplayMode", "enableTimeIndicator", "enableChapterTitleIndicator",
                "enableReadingChapterProgressIndicator", "enableSimplifiedTraditionalTransform",
                "autoPadding", "topPadding", "bottomPadding", "leftPadding", "rightPadding",
                "textColor", "textDarkColor", "backgroundColor", "backgroundDarkColor",
                "backgroundImageUri", "backgroundDarkImageUri", "backBlockMode"
            )
            fun migratedPath(path: String): String {
                val anchorPrefix = "reader.scrollRestoreAnchor"
                if (path == anchorPrefix || path.startsWith("$anchorPrefix.")) {
                    return UserDataPath.Reader.ScrollRestoreAnchor.path + path.removePrefix(anchorPrefix)
                }
                return when (path) {
                    "reader.fontLineHeight", "reader.font_line_height" -> UserDataPath.Reader.LineHeight.path
                    "reader.fontFamilyUri", "reader.font_family_uri" -> UserDataPath.Reader.FontUri.path
                    "completedDownloadBookList" -> UserDataPath.CompletedDownloadBookList.path
                    "plugin.enabledPlugins" -> UserDataPath.Plugin.EnabledPlugins.path
                    "localBook" -> UserDataPath.LocalBook.path
                    "localBookIds" -> UserDataPath.LocalBook.LocalBookIds.path
                    else -> {
                        val key = path.removePrefix("reader.")
                        if (path.startsWith("reader.") && key in readerKeys) {
                            "reader." + key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                                .lowercase(Locale.ROOT)
                        } else path
                    }
                }
            }

            val byPath = entities.associateBy { it.path }
            val fontSize = (byPath[UserDataPath.Reader.FontSize.path] ?: byPath["reader.fontSize"])
                ?.value?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 15f
            val migrated = linkedMapOf<String, UserDataEntity>()
            // 已存在的新路径优先，避免迁移旧键时覆盖更新后的设置。
            entities.sortedBy { if (migratedPath(it.path) == it.path) 1 else 0 }.forEach { entity ->
                val newPath = migratedPath(entity.path)
                val value = if (entity.path == "reader.fontLineHeight" || entity.path == "reader.font_line_height") {
                    val extraSpacing = entity.value.toFloatOrNull()?.takeIf { it.isFinite() } ?: 7f
                    ((fontSize + extraSpacing) / fontSize).toString()
                } else entity.value
                migrated[newPath] = if (newPath == entity.path) entity else entity.copy(
                    path = newPath,
                    group = newPath.substringBeforeLast('.', ""),
                    value = value
                )
            }
            val hasLegacyReaderSettings = entities.any {
                it.path.startsWith("reader.") && !it.path.startsWith("reader.scrollRestoreAnchor") &&
                    migratedPath(it.path) != it.path
            }
            val restoreDefaults = preserveLegacyDefaults || hasLegacyReaderSettings
            if (restoreDefaults && UserDataPath.Reader.FontSize.path !in migrated) {
                migrated[UserDataPath.Reader.FontSize.path] = UserDataEntity(
                    path = UserDataPath.Reader.FontSize.path,
                    group = UserDataPath.Reader.path,
                    type = "Float",
                    value = fontSize.toString()
                )
            }
            if (restoreDefaults && UserDataPath.Reader.FontWeigh.path !in migrated) {
                migrated[UserDataPath.Reader.FontWeigh.path] = UserDataEntity(
                    path = UserDataPath.Reader.FontWeigh.path,
                    group = UserDataPath.Reader.path,
                    type = "Float",
                    value = "500.0"
                )
            }
            if (restoreDefaults && UserDataPath.Reader.LineHeight.path !in migrated) {
                migrated[UserDataPath.Reader.LineHeight.path] = UserDataEntity(
                    path = UserDataPath.Reader.LineHeight.path,
                    group = UserDataPath.Reader.path,
                    type = "Float",
                    value = ((fontSize + 7f) / fontSize).toString()
                )
            }
            return migrated.values.toList()
        }
    }
}
