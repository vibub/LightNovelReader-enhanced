package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import android.content.Context
import android.net.Uri
import android.util.DisplayMetrics
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet.SimpleTextComponentContent
import indi.dmzz_yyhyy.lightnovelreader.utils.loadReaderFontFamilySafe
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderFontFamily
import io.nightfish.lightnovelreader.api.content.component.AbstractDivisibleContentComponent
import io.nightfish.lightnovelreader.api.content.component.SimpleTextComponentData
import io.nightfish.lightnovelreader.api.content.component.SimpleTextStyleRange
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle
import io.nightfish.lightnovelreader.api.ui.theme.AppTypography
import io.nightfish.lightnovelreader.api.userdata.UriUserData
import io.nightfish.lightnovelreader.api.userdata.UserDataPath
import io.nightfish.lightnovelreader.api.userdata.UserDataRepositoryApi

class SimpleTextComponent(
    data: SimpleTextComponentData,
    val userDataRepositoryApi: UserDataRepositoryApi,
    val context: Context
): AbstractDivisibleContentComponent<SimpleTextComponent, SimpleTextComponentData>(data) {

    val fontSizeUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontSize.path)
    val fontLineHeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontLineHeight.path)
    val fontWeightUserData = userDataRepositoryApi.floatUserData(UserDataPath.Reader.FontWeigh.path)
    val fontFamilyUriUserData = userDataRepositoryApi.uriUserData(UserDataPath.Reader.FontFamilyUri.path)
    val textMeasurer = TextMeasurer(
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

    override val id = SimpleTextComponentData.id

    @Composable
    override fun Content(modifier: Modifier) {
        val combinedStyle = LocalReaderStyle.current
        val annotatedText = remember(data.text, data.styleRanges) { data.toAnnotatedString() }
        SimpleTextComponentContent(
            modifier = modifier,
            text = annotatedText,
            fontSize = combinedStyle.fontSize.sp,
            fontLineHeight = combinedStyle.fontLineHeight.sp,
            fontWeight = FontWeight(combinedStyle.fontWeight.toInt()),
            fontFamily = rememberReaderFontFamily(fontFamilyUriUserData),
            color = readerTextColor(combinedStyle.textColor, combinedStyle.textDarkColor)
        )
    }

    @Composable
    private fun readerTextColor(textColor: Color, textDarkColor: Color): Color {
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

    override suspend fun split(
        height: Int,
        width: Int
    ): List<SimpleTextComponent> {
        val fontSize = fontSizeUserData.getOrDefault(15f)
        val fontLineHeight = fontLineHeightUserData.getOrDefault(7f)
        val fontWeigh = fontWeightUserData.getOrDefault(500f)
        return textMeasurer.measure(
            text = data.toAnnotatedString(),
            style = AppTypography.bodyMedium.copy(
                fontSize = fontSize.sp,
                lineHeight = (fontLineHeight + fontSize).sp,
                fontWeight = FontWeight(fontWeigh.toInt()),
                fontFamily = readerFontFamily(fontFamilyUriUserData),
            ),
            constraints = Constraints(maxHeight = height, maxWidth = width),
        )
            .getSlipData(data, width, height)
            .map { SimpleTextComponent(it, userDataRepositoryApi, context) }
    }

    private suspend fun readerFontFamily(fontFamilyUriUserData: UriUserData): FontFamily? {
        val uri = fontFamilyUriUserData.getOrDefault(Uri.EMPTY)
        return loadReaderFontFamilySafe(uri)
    }

    private fun TextLayoutResult.getSlipData(
        data: SimpleTextComponentData,
        width: Int,
        height: Int
    ): List<SimpleTextComponentData> {
        val result = mutableListOf<IntRange>()
        var lastLine = 0
        fun getNotOverflowRange(startLine: Int): IntRange {
            fun getNotOverflowLine(): Int {
                val startHeight = getLineTop(startLine)
                fun isLineOverflow(line: Int): Boolean = getLineBottom(line) > height + startHeight

                var checkLine = getLineForOffset(
                    getOffsetForPosition(
                        Offset(
                            width.toFloat(),
                            startHeight + height
                        )
                    )
                )
                while (isLineOverflow(checkLine)) checkLine--
                return checkLine
            }

            val startTextOffset = getLineStart(startLine)
            lastLine = getNotOverflowLine()
            val endTextOffset = getLineEnd(lastLine)
            lastLine++
            return startTextOffset..<endTextOffset
        }
        while (lastLine < lineCount) {
            result += getNotOverflowRange(lastLine)
        }
        return result.mapIndexedNotNull { index, range ->
            val text = data.text.slice(range)
            when (index) {
                0 if text.isBlank() -> null
                result.lastIndex if text.isBlank() -> null
                else -> data.slice(range)
            }
        }
    }
}

private fun SimpleTextComponentData.toAnnotatedString(): AnnotatedString {
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
                end = overlapEnd - start
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
