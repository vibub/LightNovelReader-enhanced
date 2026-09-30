package io.nightfish.lightnovelreader.api.content.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.identifier.Identifier

/**
 * 绑定数据的阅读组件，供需要保留实例和预排版结果的阅读器使用。
 * 新组件仍通过 [AbstractContentComponentRender] 注册，阅读器在数据与实例之间桥接。
 *
 * @param Data 组件数据类型
 * @param data 绑定的组件数据
 */
abstract class AbstractContentComponent<Data : AbstractContentComponentData>(val data: Data) {
    /** 与绑定数据一致的组件标识。 */
    open val id: Identifier get() = data.id

    /** 使用指定修饰符显示绑定的数据。 */
    @Composable
    abstract fun Content(modifier: Modifier)
}

/**
 * 可按可用尺寸分割的绑定组件。
 *
 * @param T 分割后的组件类型
 * @param Data 组件数据类型
 * @param data 绑定的组件数据
 */
abstract class AbstractDivisibleContentComponent<T : AbstractContentComponent<Data>, Data : AbstractContentComponentData>(
    data: Data
) : AbstractContentComponent<Data>(data) {
    /** 按可用像素高度和宽度分割，返回按原文顺序排列的组件。 */
    abstract suspend fun split(height: Int, width: Int): List<T>
}
