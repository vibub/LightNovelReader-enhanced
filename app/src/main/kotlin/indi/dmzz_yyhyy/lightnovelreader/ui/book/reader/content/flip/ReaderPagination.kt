package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.flip

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import indi.dmzz_yyhyy.lightnovelreader.data.content.RenderContentComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.content.component.data.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.data.Divisible
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil

private class ParagraphPage(
    private val components: List<AbstractContentComponent<*>>
) : AbstractContentComponent<AbstractContentComponentData>(components.first().data) {
    @Composable
    override fun Content(modifier: Modifier) {
        Column(modifier) {
            components.forEach { it.Content(Modifier.fillMaxWidth()) }
        }
    }
}

/** 连续 paragraph 共用一页；旧 simple_text 仍使用保留 ruby 范围的精确分割。 */
internal suspend fun paginateReaderComponents(
    components: List<AbstractContentComponent<*>>,
    height: Int,
    width: Int,
    context: Context,
    readerStyle: ReaderStyle,
    baseStyle: TextStyle,
    textStyle: TextStyle,
    measurer: TextMeasurer,
    density: Density
): List<AbstractContentComponent<*>> {
    if (height <= 0 || width <= 0) return emptyList()
    val pages = mutableListOf<AbstractContentComponent<*>>()
    val paragraphPage = mutableListOf<AbstractContentComponent<*>>()
    var occupiedHeight = 0
    val paragraphPadding = with(density) {
        ceil(readerStyle.spacingBeforeParagraph.toPx()).toInt() +
            ceil(readerStyle.spacingAfterParagraph.toPx()).toInt()
    }
    fun flushParagraphs() {
        if (paragraphPage.isEmpty()) return
        pages += ParagraphPage(paragraphPage.toList())
        paragraphPage.clear()
        occupiedHeight = 0
    }
    fun paragraphHeight(data: ParagraphComponentData): Int =
        measurer.measure(
            data.toAnnotatedString(readerStyle, baseStyle, data.index != 1),
            style = baseStyle,
            constraints = Constraints(maxWidth = width)
        ).size.height + paragraphPadding

    for (component in components) {
        currentCoroutineContext().ensureActive()
        val paragraph = component.data as? ParagraphComponentData
        if (paragraph != null && component is RenderContentComponent) {
            val pending = ArrayDeque<ParagraphComponentData>()
            pending.add(paragraph)
            while (pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val data = pending.removeFirst()
                val measuredHeight = paragraphHeight(data)
                val remaining = height - occupiedHeight
                if (measuredHeight <= remaining) {
                    paragraphPage += component.bind(data)
                    occupiedHeight += measuredHeight
                    continue
                }
                val fragments = if (remaining > paragraphPadding) {
                    data.split(remaining - paragraphPadding, width, context, readerStyle, baseStyle)
                } else emptyList()
                if (fragments.size > 1 && paragraphHeight(fragments.first()) <= remaining) {
                    paragraphPage += component.bind(fragments.first())
                    flushParagraphs()
                    fragments.drop(1).asReversed().forEach(pending::addFirst)
                } else if (paragraphPage.isNotEmpty()) {
                    flushParagraphs()
                    pending.addFirst(data)
                } else {
                    // 极小窗口不能容下一整行时也必须前进，避免重复分割死循环。
                    paragraphPage += component.bind(data)
                    flushParagraphs()
                }
            }
        } else {
            flushParagraphs()
            when {
                component is SimpleTextComponent -> pages.addAll(component.split(height, width, textStyle))
                component is AbstractDivisibleContentComponent<*, *> -> pages.addAll(component.split(height, width))
                component is RenderContentComponent && component.data is Divisible<*> -> {
                    val pending = ArrayDeque<AbstractContentComponentData>()
                    pending.add(component.data)
                    while (pending.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        val data = pending.removeFirst()
                        val parts = (data as? Divisible<*>)?.split(height, width, context, readerStyle, baseStyle)
                        if (parts != null && parts.size > 1) {
                            pages += component.bind(parts.first())
                            parts.drop(1).asReversed().forEach(pending::addFirst)
                        } else {
                            pages += component.bind(data)
                        }
                    }
                }
                else -> pages += component
            }
        }
    }
    flushParagraphs()
    return pages
}
