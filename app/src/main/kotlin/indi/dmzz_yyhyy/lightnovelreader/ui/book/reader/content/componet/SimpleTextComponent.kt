package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.componet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.nightfish.lightnovelreader.api.ui.LocalTextLocaleList

@Composable
fun SimpleTextComponentContent(
    modifier: Modifier,
    text: AnnotatedString,
    fontSize: TextUnit,
    fontLineHeight: TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily?,
    color: Color
) {
    val localeList = LocalTextLocaleList.current

    SelectionContainer {
        Text(
            modifier = modifier.fillMaxWidth(),
            text = text,
            textAlign = TextAlign.Start,
            style = MaterialTheme.typography.bodyMedium.copy(
                localeList = localeList
            ),
            fontWeight = fontWeight,
            fontSize = fontSize,
            fontFamily = fontFamily,
            color = color,
            lineHeight = (fontSize.value + fontLineHeight.value).sp
        )
    }
}