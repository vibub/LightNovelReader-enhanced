package indi.dmzz_yyhyy.lightnovelreader.data.local.cbor

import kotlinx.serialization.Serializable

/** .lnr 备份中图片、字体和背景文件的索引。 */
@Serializable
data class LocalDataArchiveManifest(
    val archiveVersion: Int = CURRENT_ARCHIVE_VERSION,
    val resources: List<Resource> = emptyList()
) {
    @Serializable
    data class Resource(
        val sourceUri: String,
        val entryName: String,
        val targetPath: String,
        val sha256: String,
        val size: Long,
        val mediaType: String,
        val extension: String
    )

    companion object {
        const val CURRENT_ARCHIVE_VERSION = 1
    }
}
