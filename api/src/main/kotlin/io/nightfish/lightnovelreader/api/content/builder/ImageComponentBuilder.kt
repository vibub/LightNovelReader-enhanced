package io.nightfish.lightnovelreader.api.content.builder

import android.net.Uri
import io.nightfish.lightnovelreader.api.content.component.ImageComponentData

/**
 * 向[ContentBuilder]中添加一个图片组件
 *
 * @param uri 图片的[Uri]
 * @param topPaddingDp 图片上方间距
 * @param bottomPaddingDp 图片下方间距
 *
 * @return 当前构建器实例, 支持链式调用
 *
 * @since Api 2
 */
fun ContentBuilder.image(
    uri: Uri,
    topPaddingDp: Int = ImageComponentData.DEFAULT_TOP_PADDING_DP,
    bottomPaddingDp: Int = ImageComponentData.DEFAULT_BOTTOM_PADDING_DP
): ContentBuilder = component(ImageComponentData(uri, topPaddingDp, bottomPaddingDp))