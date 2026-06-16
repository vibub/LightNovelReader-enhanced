package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.navigateToImageViewerDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.components.ZoomableImage
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData
import io.nightfish.lightnovelreader.api.ui.LocalNavController
import io.nightfish.lightnovelreader.api.web.WebBookDataSourceManagerApi

class ImageComponent(
    data: ImageComponentData,
    private val webBookDataSourceManagerApi: WebBookDataSourceManagerApi
): AbstractContentComponent<ImageComponentData>(data) {
    override val id = ImageComponentData.ID

    @Composable
    override fun Content(modifier: Modifier) {
        val imageHeader = remember(webBookDataSourceManagerApi.getWebDataSource()) { webBookDataSourceManagerApi.getWebDataSource().imageHeader }
        val navController = LocalNavController.current
        ZoomableImage(
            imageUri = data.uri,
            modifier = modifier
                .fillMaxSize()
                .padding(vertical = 24.dp),
            onViewImage = {
                navController.navigateToImageViewerDialog(data.uri)
            },
            header = imageHeader
        )
    }
}