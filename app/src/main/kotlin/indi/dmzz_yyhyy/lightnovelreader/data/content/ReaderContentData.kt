package indi.dmzz_yyhyy.lightnovelreader.data.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.paragraphSpacing
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import kotlin.math.roundToInt

/** 阅读器的绑定实例，与插件 API 的数据/Render 注册契约分离。 */
data class ReaderContentData(val components: List<AbstractContentComponent<*>>)

/** 为上游和插件的组件数据绑定 Render，保留原始数据，仅在显示时适配阅读器排版。 */
class RenderContentComponent(
    data: AbstractContentComponentData,
    private val render: ComponentRender,
    private val hasParagraphBefore: Boolean = false,
    private val hasParagraphAfter: Boolean = false,
    private val hasImageBefore: Boolean = false,
    private val hasImageAfter: Boolean = false
) : AbstractContentComponent<AbstractContentComponentData>(data) {
    @Composable
    override fun Content(modifier: Modifier) {
        val data = this.data
        if (data is ParagraphComponentData) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                render.Component(modifier, data)
            }
        } else if (data is ImageComponentData) {
            val density = LocalDensity.current
            val spacing = LocalReaderStyle.current.paragraphSpacing().padding(density, true, true)
            // 只在显示时调整旧默认留白，不改缓存、正文坐标和书源自定义间距。
            render.Component(modifier, data.copy(
                topPaddingDp = readerImagePaddingDp(
                    data.topPaddingDp, ImageComponentData.DEFAULT_TOP_PADDING_DP,
                    if (hasParagraphBefore) spacing.after else 0, density,
                    hasAdjacentImage = hasImageBefore
                ),
                bottomPaddingDp = readerImagePaddingDp(
                    data.bottomPaddingDp, ImageComponentData.DEFAULT_BOTTOM_PADDING_DP,
                    if (hasParagraphAfter) spacing.before else 0, density,
                    hasAdjacentImage = hasImageAfter
                )
            ))
        } else {
            render.Component(modifier, data)
        }
    }

    fun bind(data: AbstractContentComponentData): RenderContentComponent =
        RenderContentComponent(data, render)
}

/** 默认图文留白借用正文段距，连续图片内部不留白；零间距和非默认值保持原样。 */
private fun readerImagePaddingDp(
    paddingDp: Int,
    defaultPaddingDp: Int,
    paragraphPaddingPx: Int,
    density: Density,
    hasAdjacentImage: Boolean
): Int = when {
    paddingDp != defaultPaddingDp -> paddingDp
    hasAdjacentImage -> 0
    else -> with(density) { (12.dp - paragraphPaddingPx.toDp()).value.roundToInt().coerceAtLeast(0) }
}
