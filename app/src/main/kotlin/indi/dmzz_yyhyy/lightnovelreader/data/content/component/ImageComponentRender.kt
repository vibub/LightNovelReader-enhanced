package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.ui.LocalNavigator
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.navigateToImageViewerDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.components.ZoomableImage
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

class ImageComponentRender(
    private val webBookDataSourceManagerApi: WebBookDataSourceManagerApi
) : AbstractContentComponentRender<ImageComponentData>() {
    override val id = ImageComponentData.id

    @Composable
    override fun Content(modifier: Modifier, data: ImageComponentData) {
        val imageHeader =
            remember(webBookDataSourceManagerApi.getWebDataSource()) { webBookDataSourceManagerApi.getWebDataSource().imageHeader }
        val navigator = LocalNavigator.current
        ZoomableImage(
            imageUri = data.uri,
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    top = data.topPaddingDp.dp,
                    bottom = data.bottomPaddingDp.dp
                ),
            onViewImage = {
                navigator.navigateToImageViewerDialog(data.uri)
            },
            header = imageHeader
        )
    }
}