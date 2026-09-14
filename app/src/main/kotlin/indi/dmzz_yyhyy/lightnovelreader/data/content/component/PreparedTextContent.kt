package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData

/** 与章节组件一同保留，不因退出组合或切换排版环境而重复转换正文。 */
internal class PreparedTextContent(private val data: SimpleTextComponentData) {
    val hasRuby = data.styleRanges.any { !it.rubyText.isNullOrBlank() }
    // 首次进入界面与后台准备偶遇时允许重复计算，不让 UI 等待后台持有的锁。
    val text by lazy(LazyThreadSafetyMode.PUBLICATION) { data.toAnnotatedString() }
}
