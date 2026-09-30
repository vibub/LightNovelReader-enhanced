package indi.dmzz_yyhyy.lightnovelreader.data.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData

/** 阅读器的绑定实例，与插件 API 的数据/Render 注册契约分离。 */
data class ReaderContentData(val components: List<AbstractContentComponent<*>>)

/** 为上游和插件的组件数据绑定 Render，不复制或改写原始章节数据。 */
class RenderContentComponent(
    data: AbstractContentComponentData,
    private val render: ComponentRender
) : AbstractContentComponent<AbstractContentComponentData>(data) {
    @Composable
    override fun Content(modifier: Modifier) {
        if (data is io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                render.Component(modifier, data)
            }
        } else {
            render.Component(modifier, data)
        }
    }

    fun bind(data: AbstractContentComponentData): RenderContentComponent =
        RenderContentComponent(data, render)
}
