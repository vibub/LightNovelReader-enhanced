package indi.dmzz_yyhyy.lightnovelreader.data.update

import java.io.File

interface Release {
    val version: Int
    val versionName: String
    val releaseNotes: String
    val downloadUrl: String
    val downloadUrls: List<String>
        get() = listOf(downloadUrl)

    /***
     * 第一个File是需要处理的文件
     * 第二个File是处理好后应存储的位置
     */
    val downloadFileProgress: ((File, File) -> Unit)?
        get() = null
}