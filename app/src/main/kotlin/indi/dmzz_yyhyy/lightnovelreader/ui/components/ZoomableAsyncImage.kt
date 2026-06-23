package indi.dmzz_yyhyy.lightnovelreader.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.R

private const val DEBUG_READER_IMAGE = false
private const val READER_IMAGE_LOG_TAG = "ReaderImageDbg"

internal object ReaderImageHeightCache {
    private const val MAX_READER_IMAGE_HEIGHT_CACHE_SIZE = 512
    private val cache = LruCache<String, Int>(MAX_READER_IMAGE_HEIGHT_CACHE_SIZE)

    private fun key(imageUri: String, widthPx: Int): String = "$widthPx:$imageUri"

    fun get(imageUri: String, widthPx: Int): Int? = synchronized(cache) {
        cache.get(key(imageUri, widthPx.coerceAtLeast(1)))
    }

    fun put(imageUri: String, widthPx: Int, heightPx: Int) {
        if (heightPx <= 0) return
        val key = key(imageUri, widthPx.coerceAtLeast(1))
        synchronized(cache) {
            if (cache.get(key) != heightPx) {
                cache.put(key, heightPx)
            }
        }
    }
}

@Suppress("SimplifyBooleanWithConstants")
private inline fun debugImageLog(message: () -> String) {
    if (BuildConfig.DEBUG && DEBUG_READER_IMAGE) Log.d(READER_IMAGE_LOG_TAG, message())
}

private fun scaledReaderImageHeightPx(targetWidthPx: Int, sourceWidthPx: Int, sourceHeightPx: Int): Int? {
    if (targetWidthPx <= 0 || sourceWidthPx <= 0 || sourceHeightPx <= 0) return null
    return (targetWidthPx.toLong() * sourceHeightPx / sourceWidthPx)
        .toInt()
        .coerceAtLeast(1)
}

internal suspend fun preloadReaderImageHeight(
    context: Context,
    imageUri: Uri,
    widthPx: Int,
    header: Map<String, String> = emptyMap()
): Int? = withContext(Dispatchers.IO) {
    val targetWidthPx = widthPx.coerceAtLeast(1)
    val imageUriString = imageUri.toString()
    ReaderImageHeightCache.get(imageUriString, targetWidthPx)?.let { return@withContext it }

    val result = runCatching {
        val request = ImageRequest.Builder(context)
            .data(imageUri)
            .size(Size(targetWidthPx, Dimension.Undefined))
            .crossfade(false)
            .interceptorCoroutineContext(Dispatchers.IO)
            .httpHeaders(
                NetworkHeaders.Builder().apply {
                    header.forEach { (key, value) -> add(key, value) }
                }.build()
            )
            .build()
        context.imageLoader.execute(request)
    }.getOrElse { throwable ->
        debugImageLog { "preloadFailed uri=${imageUri.shortForLog()} error=${throwable.localizedMessage}" }
        return@withContext null
    }

    when (result) {
        is SuccessResult -> {
            val heightPx = scaledReaderImageHeightPx(
                targetWidthPx = targetWidthPx,
                sourceWidthPx = result.image.width,
                sourceHeightPx = result.image.height
            ) ?: return@withContext null
            ReaderImageHeightCache.put(imageUriString, targetWidthPx, heightPx)
            debugImageLog { "preloadSuccess uri=${imageUri.shortForLog()} height=$heightPx" }
            heightPx
        }
        is ErrorResult -> {
            debugImageLog { "preloadError uri=${imageUri.shortForLog()} error=${result.throwable.localizedMessage}" }
            null
        }
    }
}

private fun Uri.shortForLog(): String {
    val value = toString()
    return if (value.length <= 96) value else value.take(72) + "..." + value.takeLast(16)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZoomableImage(
    imageUri: Uri,
    modifier: Modifier = Modifier,
    onViewImage: () -> Unit,
    placeholderHeight: Dp = 200.dp,
    header: Map<String, String>
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenWidthPx = LocalResources.current.displayMetrics.widthPixels.coerceAtLeast(1)
    val imageUriString = remember(imageUri) { imageUri.toString() }
    var retryKey by remember { mutableIntStateOf(0) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val cachedImageHeightPx = remember(imageUriString, screenWidthPx) { ReaderImageHeightCache.get(imageUriString, screenWidthPx) }
    val reservedImageHeight = cachedImageHeightPx
        ?.takeIf { it > 0 }
        ?.let { with(density) { it.toDp() } }
        ?: placeholderHeight
    val reservedImageHeightPx = with(density) { reservedImageHeight.roundToPx() }
    val lastMeasuredHeightPx = remember(imageUriString, reservedImageHeightPx) {
        intArrayOf(cachedImageHeightPx ?: reservedImageHeightPx)
    }

    Box(modifier = modifier) {
        key(retryKey) {
            val imageRequest = remember(imageUri, header, retryKey, screenWidthPx) {
                ImageRequest.Builder(context)
                    .data(imageUri)
                    .size(Size(screenWidthPx, Dimension.Undefined))
                    .crossfade(false)
                    .interceptorCoroutineContext(Dispatchers.Default)
                    .listener(
                        onSuccess = { _, _ -> lastError = null },
                        onError = { _, result -> lastError = result.throwable.localizedMessage }
                    )
                    .httpHeaders(
                        NetworkHeaders.Builder().apply {
                            header.forEach { (key, value) -> add(key, value) }
                        }.build()
                    )
                    .build()
            }
            val painter = rememberAsyncImagePainter(model = imageRequest)
            val state by painter.state.collectAsState()
            if (DEBUG_READER_IMAGE) {
                LaunchedEffect(state::class.simpleName, retryKey) {
                    debugImageLog {
                        "state uri=${imageUri.shortForLog()} retry=$retryKey state=${state::class.simpleName} " +
                                "placeholder=$placeholderHeight lastError=${lastError?.take(96)} headerKeys=${header.keys.joinToString(prefix = "[", postfix = "]")}"
                    }
                }
            }
            when (state) {
                is AsyncImagePainter.State.Loading -> {
                    Box(
                        modifier = Modifier
                            .height(reservedImageHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Loading()
                    }
                }

                is AsyncImagePainter.State.Error -> {
                    Column(
                        modifier = Modifier
                            .height(reservedImageHeight)
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            modifier = Modifier.size(36.dp),
                            painter = painterResource(R.drawable.release_alert_24px),
                            tint = colorScheme.secondary,
                            contentDescription = null
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("图片加载失败", style = typography.labelLarge)
                        lastError?.let {
                            Text(
                                text = it,
                                style = typography.labelMedium,
                                color = colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            retryKey++
                            lastError = null
                        }) {
                            Text("重试")
                        }
                    }
                }

                is AsyncImagePainter.State.Success -> {
                    val successPainter = (state as AsyncImagePainter.State.Success).painter
                    val reservedHeightModifier = if (cachedImageHeightPx != null) {
                        Modifier.height(reservedImageHeight)
                    } else {
                        Modifier.heightIn(min = reservedImageHeight)
                    }
                    Image(
                        painter = successPainter,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(reservedHeightModifier)
                            .align(Alignment.Center)
                            .pointerInput(onViewImage) {
                                awaitPointerEventScope {
                                    val longPressMillis = 380L
                                    val twoFingerTapMaxMillis = 280L
                                    val slop = viewConfiguration.touchSlop

                                    while (true) {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val t0 = down.uptimeMillis

                                        var twoFingerClick = false

                                        val startPos = linkedMapOf(down.id to down.position)
                                        var maxMove = 0f

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressedChanges = event.changes.filter { it.pressed }

                                            pressedChanges.forEach { ch ->
                                                if (!startPos.containsKey(ch.id)) startPos[ch.id] = ch.position
                                                val sp = startPos[ch.id]!!
                                                val dx = ch.position.x - sp.x
                                                val dy = ch.position.y - sp.y
                                                val dist = kotlin.math.hypot(dx, dy)
                                                if (dist > maxMove) maxMove = dist
                                            }

                                            if (!twoFingerClick && pressedChanges.size >= 2) {
                                                twoFingerClick = true
                                            }

                                            /**
                                             * 单指长按且未位移，调用 onViewImage()
                                             * 并消费点击以防止触发阅读器沉浸切换
                                             * */
                                            if (!twoFingerClick) {
                                                val now = event.changes.firstOrNull { it.id == down.id }?.uptimeMillis
                                                    ?: down.uptimeMillis
                                                val elapsed = now - t0

                                                if (elapsed >= longPressMillis && maxMove <= slop) {
                                                    event.changes.forEach { it.consume() }
                                                    onViewImage()
                                                    break
                                                }

                                            }

                                            /**
                                             * 双指轻点且未位移，调用 onViewImage()
                                             * 并消费点击以防止触发阅读器沉浸切换
                                             * */
                                            if (pressedChanges.isEmpty()) {
                                                if (twoFingerClick) {
                                                    val elapsed = (event.changes.maxOfOrNull { it.uptimeMillis } ?: t0) - t0
                                                    if (elapsed <= twoFingerTapMaxMillis && maxMove <= slop) {
                                                        event.changes.forEach { it.consume() }
                                                        onViewImage()
                                                    }
                                                }
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            .onGloballyPositioned {
                                val heightPx = it.size.height
                                if (heightPx <= 0 || lastMeasuredHeightPx[0] == heightPx) return@onGloballyPositioned
                                val previousCachedHeight = if (DEBUG_READER_IMAGE) ReaderImageHeightCache.get(imageUriString, screenWidthPx) else null
                                val previousHeightPx = lastMeasuredHeightPx[0]
                                lastMeasuredHeightPx[0] = heightPx
                                ReaderImageHeightCache.put(imageUriString, screenWidthPx, heightPx)
                                debugImageLog {
                                    "successContentSize uri=${imageUri.shortForLog()} retry=$retryKey " +
                                            "new=${it.size.width}x${it.size.height} placeholder=$placeholderHeight " +
                                            "previousHeight=$previousHeightPx cachedHeightBefore=$previousCachedHeight cachedHeightAfter=$heightPx"
                                }
                            }
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .height(reservedImageHeight)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
