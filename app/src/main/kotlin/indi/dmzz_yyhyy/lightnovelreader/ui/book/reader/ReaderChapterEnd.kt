package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.book.lastLinovelibChapterPageId
import io.nightfish.lightnovelreader.api.book.ChapterContent

@Composable
fun ReaderChapterEnd(
    context: ChapterEndContext,
    nextChapterTitle: String?,
    contentColor: Color,
    onClickComments: (ChapterEndContext) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = contentColor.copy(alpha = 0.35f)
            )
            Text(
                text = stringResource(R.string.reader_chapter_end),
                style = typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.8f)
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = contentColor.copy(alpha = 0.35f)
            )
        }

        Spacer(Modifier.height(18.dp))
        FilledTonalButton(onClick = { onClickComments(context) }) {
            Icon(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.forum_24px),
                contentDescription = null
            )
            Text(
                modifier = Modifier.padding(start = 8.dp),
                text = stringResource(R.string.chapter_comments_view)
            )
        }

        nextChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
            Spacer(Modifier.height(14.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.reader_next_chapter_title, title),
                style = typography.bodySmall,
                color = contentColor.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun ChapterContent.toChapterEndContext(bookId: String) = ChapterEndContext(
    bookId = bookId,
    chapterId = id.substringBefore('_'),
    chapterTitle = title,
    refererChapterPageId = content.lastLinovelibChapterPageId(id)
)
