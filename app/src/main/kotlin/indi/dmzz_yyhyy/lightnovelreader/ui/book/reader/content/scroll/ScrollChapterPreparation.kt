package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ChapterContentUiState
import io.nightfish.lightnovelreader.api.book.ChapterContent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 跨章重新订阅时保留现有组件，正文比较和反序列化都不占用 UI 线程。 */
internal suspend fun prepareScrollChapter(
    chapter: ChapterContent,
    existing: ChapterContentUiState?,
    createComponents: (JsonObject) -> List<AbstractContentComponent<*>>
): ChapterContentUiState = withContext(Dispatchers.Default) {
    val reusable = existing?.takeIf {
        it.id == chapter.id && it.sourceContent == chapter.content
    }
    if (reusable != null && reusable.title == chapter.title &&
        reusable.prevChapter == chapter.prevChapter && reusable.nextChapter == chapter.nextChapter
    ) {
        return@withContext reusable
    }
    ChapterContentUiState(
        id = chapter.id,
        title = chapter.title,
        content = reusable?.content ?: createComponents(chapter.content).also { components ->
            // 章节进入 UI 状态前准备注释正文，后台排版和首次显示共享同一实例。
            components.filterIsInstance<SimpleTextComponent>().forEach {
                if (it.preparedText.hasRuby) it.preparedText.text
            }
        },
        sourceContent = chapter.content,
        prevChapter = chapter.prevChapter,
        nextChapter = chapter.nextChapter
    )
}
