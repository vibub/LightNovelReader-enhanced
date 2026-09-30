package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Context
import android.net.Uri
import android.util.DisplayMetrics
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.LocalReaderRubyTextCache
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.readerRubyTextStyle
import indi.dmzz_yyhyy.lightnovelreader.utils.loadReaderFontFamilySafe
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentRender
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.ReaderStyle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

class SimpleTextComponentRender(
    private val userDataRepositoryApi: UserDataRepositoryApi,
    private val context: Context
) : AbstractContentComponentRender<SimpleTextComponentData>() {
    override val id = SimpleTextComponentData.id

    @Composable
    override fun Content(modifier: Modifier, data: SimpleTextComponentData) {
        SimpleTextComponent(data, userDataRepositoryApi, context).Content(modifier)
    }
}

class SimpleTextComponent(
    data: SimpleTextComponentData,
    val userDataRepositoryApi: UserDataRepositoryApi,
    val context: Context,
    private val startsParagraph: Boolean = true,
    private val endsParagraph: Boolean = true
) : AbstractDivisibleContentComponent<SimpleTextComponent, SimpleTextComponentData>(data) {

    val fontSizeUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontSize.path)
    val fontLineHeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.LineHeight.path)
    val fontWeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontWeigh.path)
    val fontFamilyUriUserData = userDataRepositoryApi.uriUserData(UserDataPath.Reader.FontUri.path)
    val textMeasurer by lazy {
        TextMeasurer(
            createFontFamilyResolver(context),
            Density(
                context.resources.configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT,
                context.resources.configuration.fontScale,
            ),
            if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
                LayoutDirection.Ltr
            } else {
                LayoutDirection.Rtl
            }
        )
    }

    override val id = SimpleTextComponentData.id
    internal val preparedText = PreparedTextContent(data, startsParagraph, endsParagraph)

    @Composable
    override fun Content(modifier: Modifier) {
        val combinedStyle = LocalReaderStyle.current
        val color = readerContentTextColor(combinedStyle.textColor, combinedStyle.textDarkColor)
        val style = readerRubyTextStyle(
            combinedStyle,
            LocalReaderRubyTextCache.current?.environment?.style?.fontFamily
                ?: rememberReaderFontFamily(fontFamilyUriUserData),
            color
        )
        SimpleTextComponentContent(
            modifier = modifier,
            text = preparedText.text,
            style = style,
            color = color,
            styleRanges = data.styleRanges,
            paragraphs = preparedText.paragraphs,
            paragraphSpacing = combinedStyle.paragraphSpacing(),
            preparedKey = preparedText.layoutKey
        )
    }

    override suspend fun split(
        height: Int,
        width: Int
    ): List<SimpleTextComponent> {
        val fontSize = fontSizeUserData.getOrDefault(16f)
        val lineHeight = fontLineHeightUserData.getOrDefault(1.4f)
        val fontWeigh = fontWeightUserData.getOrDefault(400f)
        val readerStyle = ReaderStyle(
            fontSize = fontSize.sp,
            lineHeight = lineHeight.em,
            fontWeight = FontWeight(fontWeigh.toInt()),
            letterSpacing = userDataRepositoryApi.floatUserData(UserDataPath.Reader.LetterSpacing.path).getOrDefault(0.2f).sp,
            spacingBeforeParagraph = userDataRepositoryApi.floatUserData(UserDataPath.Reader.SpacingBeforeParagraph.path).getOrDefault(0f).sp,
            spacingAfterParagraph = userDataRepositoryApi.floatUserData(UserDataPath.Reader.SpacingAfterParagraph.path).getOrDefault(16f).sp,
            textIndent = TextIndent(userDataRepositoryApi.floatUserData(UserDataPath.Reader.FirstLineTextIndent.path).getOrDefault(2f).em)
        )
        val style = AppTypography.bodyMedium.copy(
            fontSize = readerStyle.fontSize,
            lineHeight = readerStyle.lineHeight,
            letterSpacing = readerStyle.letterSpacing,
            fontWeight = readerStyle.fontWeight,
            fontFamily = readerFontFamily(fontFamilyUriUserData),
            textIndent = readerStyle.textIndent
        )
        return split(height, width, style, readerStyle)
    }

    suspend fun split(
        height: Int,
        width: Int,
        style: TextStyle,
        readerStyle: ReaderStyle = ReaderStyle(),
        measurer: TextMeasurer = textMeasurer,
        density: Density = Density(context.resources.displayMetrics.density, context.resources.configuration.fontScale)
    ): List<SimpleTextComponent> {
        if (height <= 0 || width <= 0) return listOf(this)
        val coroutineContext = currentCoroutineContext()
        val layout = measureReaderText(
            preparedText.text, data.styleRanges, preparedText.paragraphs,
            style, readerStyle.paragraphSpacing(), measurer, density, width
        ) { coroutineContext.ensureActive() }
        val paragraphStarts = preparedText.paragraphs.filter { it.startsParagraph }.mapTo(mutableSetOf()) { it.start }
        val paragraphEnds = preparedText.paragraphs.filter { it.endsParagraph }.mapTo(mutableSetOf()) { it.end }
        return layout.pageRanges(height).mapNotNull { range ->
            coroutineContext.ensureActive()
            data.slice(range).takeIf { it.text.isNotBlank() }?.let {
                SimpleTextComponent(it, userDataRepositoryApi, context,
                    startsParagraph = range.first in paragraphStarts,
                    endsParagraph = range.last + 1 in paragraphEnds
                )
            }
        }
    }

    private suspend fun readerFontFamily(fontFamilyUriUserData: UriUserData): FontFamily? {
        val uri = fontFamilyUriUserData.getOrDefault(Uri.EMPTY)
        return loadReaderFontFamilySafe(uri)
    }

}

@Composable
internal fun readerContentTextColor(textColor: Color, textDarkColor: Color): Color {
    val localTheme = LocalAppTheme.current
    val isDark = localTheme.isDark
    val onSurface = localTheme.colorScheme.onSurface

    return remember(isDark, textColor, textDarkColor, onSurface) {
        when {
            isDark && textDarkColor.isUnspecified -> onSurface
            !isDark && textColor.isUnspecified -> onSurface
            isDark -> textDarkColor
            else -> textColor
        }
    }
}

internal fun SimpleTextComponentData.toAnnotatedString(): AnnotatedString {
    if (styleRanges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        styleRanges.forEach { range ->
            val start = range.start.coerceIn(0, text.length)
            val end = range.end.coerceIn(start, text.length)
            if (start < end) addStyle(range.toSpanStyle(), start, end)
        }
    }
}

private fun SimpleTextComponentData.slice(range: IntRange): SimpleTextComponentData {
    val start = range.first.coerceIn(0, text.length)
    val end = (range.last + 1).coerceIn(start, text.length)
    return SimpleTextComponentData(
        text = text.slice(start..<end),
        styleRanges = styleRanges.mapNotNull { styleRange ->
            val overlapStart = maxOf(styleRange.start, start)
            val overlapEnd = minOf(styleRange.end, end)
            if (overlapStart >= overlapEnd) return@mapNotNull null
            styleRange.copy(
                start = overlapStart - start,
                end = overlapEnd - start,
                rubyText = styleRange.rubySubstring(overlapStart, overlapEnd)
            )
        }
    )
}

private fun SimpleTextStyleRange.toSpanStyle(): SpanStyle {
    val decorations = buildList {
        if (underline) add(TextDecoration.Underline)
        if (strikethrough) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = fontWeight?.let { FontWeight(it.coerceIn(1, 1000)) },
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = when (decorations.size) {
            0 -> null
            1 -> decorations.single()
            else -> TextDecoration.combine(decorations)
        }
    )
}
