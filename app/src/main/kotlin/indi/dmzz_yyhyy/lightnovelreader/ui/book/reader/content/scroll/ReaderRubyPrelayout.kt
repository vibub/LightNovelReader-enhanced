package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.github.michaelbull.result.get
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.SimpleTextComponent
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.toAnnotatedString
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.ReaderRubyTextCache
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.RubyTextEnvironment
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.RubyTextKey
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.prepareRubyText
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.readerRubyTextStyle
import indi.dmzz_yyhyy.lightnovelreader.data.content.component.readerContentTextColor
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val rubyPrelayoutDispatcher = Dispatchers.Default.limitedParallelism(1)

@Composable
internal fun rememberReaderRubyTextCache(
    uiState: ScrollContentUiState,
    settingState: SettingState,
    width: Int
): ReaderRubyTextCache {
    val readerStyle = LocalReaderStyle.current
    val density = LocalDensity.current
    val environment = RubyTextEnvironment(
        style = readerRubyTextStyle(
            readerStyle.fontSize.sp,
            readerStyle.fontLineHeight.sp,
            FontWeight(readerStyle.fontWeight.toInt()),
            rememberReaderFontFamily(settingState.fontFamilyUriUserData),
            readerContentTextColor(readerStyle.textColor, readerStyle.textDarkColor)
        ),
        density = Density(density.density, density.fontScale),
        direction = LocalLayoutDirection.current,
        resolver = LocalFontFamilyResolver.current,
        width = width
    )
    val cache = remember(uiState.bookId, environment) { ReaderRubyTextCache(environment) }
    LaunchedEffect(cache, uiState.lazyListState) {
        if (width <= 0) return@LaunchedEffect
        // 首次定位仍走精确同步布局，不让后台任务争抢打开当前章节的 CPU。
        snapshotFlow { uiState.isInitialPositioned }.first { it }
        snapshotFlow {
            // 优先下一章，其次上一章；不根据每帧的滚动方向取消和重做排版。
            listOf(2, 0, 1).flatMap { index ->
                uiState.contentList.getOrNull(index)?.second?.get()?.content.orEmpty()
                    .filterIsInstance<SimpleTextComponent>()
                    .map { it.data }
                    .filter { data -> data.styleRanges.any { !it.rubyText.isNullOrBlank() } }
            }
        }.collectLatest { data ->
            val keys = withContext(rubyPrelayoutDispatcher) {
                data.map {
                    currentCoroutineContext().ensureActive()
                    RubyTextKey(it.toAnnotatedString(), it.styleRanges)
                }.distinct()
            }
            // 内容相同的重新订阅不失效，退出三个章节窗口的布局立即释放。
            cache.retain(keys.toSet())
            for (key in keys) {
                val existing = cache.get(key, environment)
                if (existing != null && !existing.hasStaleFonts) continue
                try {
                    val prepared = withContext(rubyPrelayoutDispatcher) {
                        val context = currentCoroutineContext()
                        prepareRubyText(key, environment, environment.newMeasurer()) {
                            context.ensureActive()
                        }
                    }
                    // 缓存只在主线程读写；不等待锁，也不在 UI 线程等待后台结果。
                    // 若用户已经跨章并完成同步布局，保留 UI 正在使用的那个实例。
                    val current = cache.get(key, environment)
                    if (current == null || current.hasStaleFonts) {
                        cache.put(key, environment, prepared)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("ReaderRubyPrelayout", "注释预排版失败，进入章节时使用原有布局路径", error)
                }
            }
        }
    }
    return cache
}
