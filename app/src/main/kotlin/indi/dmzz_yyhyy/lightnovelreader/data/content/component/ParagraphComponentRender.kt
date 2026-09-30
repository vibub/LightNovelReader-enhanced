package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalAppTheme
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.ParagraphComponentData
import io.nightfish.lightnovelreader.api.ui.LocalReaderStyle

class ParagraphComponentRender: AbstractContentComponentRender<ParagraphComponentData>() {
    override val id = ParagraphComponentData.id

    @Composable
    override fun Content(
        modifier: Modifier,
        data: ParagraphComponentData
    ) {
        val density = LocalDensity.current
        val readerStyle = LocalReaderStyle.current
        val padding = readerStyle.paragraphSpacing().padding(density, data.index == 1, data.endsParagraph)

        Text(
            data.toAnnotatedString(
                readerStyle,
                MaterialTheme.typography.bodyMedium,
                data.index != 1,
                LocalAppTheme.current.isDark
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(
                bottom = with(density) {
                    padding.after.toDp()
                },
                top = with(density) {
                    padding.before.toDp()
                }
            )
        )
    }
}