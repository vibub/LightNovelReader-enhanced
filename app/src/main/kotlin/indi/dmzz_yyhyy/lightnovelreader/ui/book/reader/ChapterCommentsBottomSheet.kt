package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import indi.dmzz_yyhyy.lightnovelreader.R
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibChapterComment
import indi.dmzz_yyhyy.lightnovelreader.defaultplugin.linovelib.comment.LinovelibCommentQuote
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterCommentsBottomSheet(
    sheetState: SheetState,
    uiState: ChapterCommentsUiState,
    avatarHeaders: Map<String, String>,
    onDismissRequest: () -> Unit,
    onSelectTab: (ChapterCommentTab) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetryHot: () -> Unit,
    onRetryAll: () -> Unit,
    onLogin: () -> Unit
) {
    val hotListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val revealedSpoilers = remember(uiState.context?.chapterId, uiState.isVisible) {
        mutableStateMapOf<String, Boolean>()
    }
    val expandedQuotes = remember(uiState.context?.chapterId, uiState.isVisible) {
        mutableStateMapOf<String, Boolean>()
    }

    LaunchedEffect(
        uiState.selectedTab,
        uiState.allComments.size,
        uiState.hasMoreAll,
        uiState.isLoadingAll,
        uiState.allError
    ) {
        if (uiState.selectedTab != ChapterCommentTab.All ||
            uiState.allComments.isEmpty() ||
            !uiState.hasMoreAll ||
            uiState.isLoadingAll ||
            uiState.allError != null
        ) {
            return@LaunchedEffect
        }
        snapshotFlow {
            allListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }
            .filter { lastVisibleIndex ->
                lastVisibleIndex >= (uiState.allComments.lastIndex - 2).coerceAtLeast(0)
            }
            .first()
        onLoadNextPage()
    }

    ReaderBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismissRequest,
        maxHeightFraction = 0.92f,
        showCloseButton = true,
        titleIcon = {
            Icon(
                painter = painterResource(R.drawable.forum_24px),
                contentDescription = null
            )
        },
        title = {
            Column {
                Text(
                    text = uiState.totalCount?.let { count ->
                        stringResource(R.string.chapter_comments_title_count, count)
                    } ?: stringResource(R.string.chapter_comments_title),
                    style = typography.displayMedium,
                    fontWeight = FontWeight.W600
                )
                uiState.participantCount?.let { count ->
                    Text(
                        text = stringResource(R.string.chapter_comments_participant_count, count),
                        style = typography.labelMedium,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        PrimaryTabRow(
            selectedTabIndex = uiState.selectedTab.ordinal,
            containerColor = colorScheme.surface
        ) {
            Tab(
                selected = uiState.selectedTab == ChapterCommentTab.Hot,
                onClick = { onSelectTab(ChapterCommentTab.Hot) },
                text = { Text(stringResource(R.string.chapter_comments_hot_tab)) }
            )
            Tab(
                selected = uiState.selectedTab == ChapterCommentTab.All,
                onClick = { onSelectTab(ChapterCommentTab.All) },
                text = { Text(stringResource(R.string.chapter_comments_all_tab)) }
            )
        }

        if (uiState.cookieExpired ||
            (uiState.selectedTab == ChapterCommentTab.All &&
                (!uiState.hasCookie || uiState.allError == ChapterCommentError.LoginRequired))
        ) {
            ChapterCommentsLoginPrompt(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                loginExpired = uiState.cookieExpired || uiState.allError == ChapterCommentError.LoginRequired,
                onLogin = onLogin
            )
        } else {
            val isHot = uiState.selectedTab == ChapterCommentTab.Hot
            ChapterCommentList(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                listState = if (isHot) hotListState else allListState,
                comments = if (isHot) uiState.hotComments else uiState.allComments,
                isLoading = if (isHot) uiState.isLoadingHot else uiState.isLoadingAll,
                error = if (isHot) uiState.hotError else uiState.allError,
                isPaging = !isHot && uiState.allComments.isNotEmpty(),
                avatarHeaders = avatarHeaders,
                revealedSpoilers = revealedSpoilers,
                expandedQuotes = expandedQuotes,
                onRetry = if (isHot) onRetryHot else onRetryAll
            )
        }
    }
}

@Composable
private fun ChapterCommentsLoginPrompt(
    modifier: Modifier,
    loginExpired: Boolean,
    onLogin: () -> Unit
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    modifier = Modifier.size(40.dp),
                    painter = painterResource(if (loginExpired) R.drawable.warning_24px else R.drawable.person_24px),
                    contentDescription = null,
                    tint = colorScheme.primary
                )
                Text(
                    text = stringResource(
                        if (loginExpired) R.string.chapter_comments_cookie_expired
                        else R.string.chapter_comments_login_required
                    ),
                    style = typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Button(onClick = onLogin) {
                    Text(
                        stringResource(
                            if (loginExpired) R.string.chapter_comments_relogin_action
                            else R.string.chapter_comments_login_action
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterCommentList(
    modifier: Modifier,
    listState: LazyListState,
    comments: List<LinovelibChapterComment>,
    isLoading: Boolean,
    error: ChapterCommentError?,
    isPaging: Boolean,
    avatarHeaders: Map<String, String>,
    revealedSpoilers: MutableMap<String, Boolean>,
    expandedQuotes: MutableMap<String, Boolean>,
    onRetry: () -> Unit
) {
    if (comments.isEmpty()) {
        Box(
            modifier = modifier.padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> ChapterCommentsLoading()
                error != null -> ChapterCommentErrorState(error = error, onRetry = onRetry)
                else -> Text(
                    text = stringResource(R.string.chapter_comments_empty),
                    style = typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = comments,
            key = { it.id }
        ) { comment ->
            ChapterCommentCard(
                comment = comment,
                avatarHeaders = avatarHeaders,
                spoilerRevealed = revealedSpoilers[comment.id] == true,
                quotesExpanded = expandedQuotes[comment.id] == true,
                onRevealSpoiler = { revealedSpoilers[comment.id] = true },
                onToggleQuotes = {
                    expandedQuotes[comment.id] = expandedQuotes[comment.id] != true
                }
            )
        }

        if (isLoading) {
            item(key = "comment-loading") {
                ChapterCommentsLoading(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        } else if (error != null) {
            item(key = "comment-error") {
                ChapterCommentPagingError(
                    error = error,
                    isPaging = isPaging,
                    onRetry = onRetry
                )
            }
        }
    }
}

@Composable
private fun ChapterCommentsLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp))
        Text(
            text = stringResource(R.string.chapter_comments_loading),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChapterCommentErrorState(
    error: ChapterCommentError,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            modifier = Modifier.size(40.dp),
            painter = painterResource(R.drawable.error_24px),
            contentDescription = null,
            tint = colorScheme.error
        )
        Text(
            text = stringResource(R.string.chapter_comments_load_failed),
            style = typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = chapterCommentErrorText(error),
            style = typography.bodyMedium,
            color = colorScheme.onSurfaceVariant
        )
        Button(onClick = onRetry) {
            Text(stringResource(R.string.chapter_comments_retry))
        }
    }
}

@Composable
private fun ChapterCommentPagingError(
    error: ChapterCommentError,
    isPaging: Boolean,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = if (isPaging) {
                stringResource(R.string.chapter_comments_load_more_failed)
            } else {
                stringResource(R.string.chapter_comments_load_failed)
            },
            style = typography.labelLarge,
            color = colorScheme.error
        )
        Text(
            text = chapterCommentErrorText(error),
            style = typography.bodySmall,
            color = colorScheme.onSurfaceVariant
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.chapter_comments_retry))
        }
    }
}

@Composable
private fun chapterCommentErrorText(error: ChapterCommentError): String = stringResource(
    when (error) {
        ChapterCommentError.Network -> R.string.chapter_comments_error_network
        ChapterCommentError.RateLimited -> R.string.chapter_comments_error_rate_limited
        ChapterCommentError.Cloudflare -> R.string.chapter_comments_error_cloudflare
        ChapterCommentError.LoginRequired -> R.string.chapter_comments_cookie_expired
        ChapterCommentError.Protocol -> R.string.chapter_comments_error_protocol
    }
)

@Composable
private fun ChapterCommentCard(
    comment: LinovelibChapterComment,
    avatarHeaders: Map<String, String>,
    spoilerRevealed: Boolean,
    quotesExpanded: Boolean,
    onRevealSpoiler: () -> Unit,
    onToggleQuotes: () -> Unit
) {
    val username = comment.username.ifBlank {
        stringResource(R.string.chapter_comments_unknown_user)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        color = colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                ChapterCommentAvatar(
                    avatarUrl = comment.avatarUrl,
                    username = username,
                    avatarHeaders = avatarHeaders
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = username,
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (comment.honor.isNotBlank()) {
                            ChapterCommentTag(text = comment.honor)
                        }
                        if (comment.isSpoiler) {
                            ChapterCommentTag(
                                text = stringResource(R.string.chapter_comments_spoiler),
                                showWarningIcon = true
                            )
                        }
                        if (comment.isControversial) {
                            ChapterCommentTag(
                                text = stringResource(R.string.chapter_comments_controversial),
                                showWarningIcon = true
                            )
                        }
                    }
                    if (comment.publishedAt.isNotBlank()) {
                        Text(
                            text = comment.publishedAt,
                            style = typography.labelSmall,
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if ((comment.isSpoiler || comment.isControversial) && !spoilerRevealed) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onRevealSpoiler),
                    shape = RoundedCornerShape(12.dp),
                    color = colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.warning_24px),
                            contentDescription = null,
                            tint = colorScheme.onTertiaryContainer
                        )
                        Text(
                            text = stringResource(
                                if (comment.isControversial) R.string.chapter_comments_controversial_hidden
                                else R.string.chapter_comments_spoiler_hidden
                            ),
                            style = typography.bodyMedium,
                            color = colorScheme.onTertiaryContainer
                        )
                    }
                }
            } else {
                if (comment.quotedReplies.isNotEmpty()) {
                    ChapterCommentQuotes(
                        quotes = comment.quotedReplies,
                        expanded = quotesExpanded,
                        onToggle = onToggleQuotes
                    )
                }
                Text(
                    text = comment.body,
                    style = typography.bodyMedium,
                    color = colorScheme.onSurface
                )
            }

            HorizontalDivider(color = colorScheme.outlineVariant)
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChapterCommentMetric(
                    iconRes = R.drawable.thumb_up_24px,
                    count = comment.likeCount,
                    contentDescription = stringResource(
                        R.string.chapter_comments_like_count,
                        comment.likeCount
                    )
                )
                ChapterCommentMetric(
                    iconRes = R.drawable.thumb_down_24px,
                    count = comment.dislikeCount,
                    contentDescription = stringResource(
                        R.string.chapter_comments_dislike_count,
                        comment.dislikeCount
                    )
                )
            }
        }
    }
}

@Composable
private fun ChapterCommentAvatar(
    avatarUrl: String,
    username: String,
    avatarHeaders: Map<String, String>
) {
    val context = LocalContext.current
    val avatarModifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
    val avatarDescription = stringResource(R.string.chapter_comments_avatar_description, username)

    if (avatarUrl.isBlank()) {
        ChapterCommentAvatarPlaceholder(
            modifier = avatarModifier,
            contentDescription = avatarDescription
        )
        return
    }

    val request = remember(avatarUrl, avatarHeaders, context) {
        ImageRequest.Builder(context)
            .data(avatarUrl)
            .httpHeaders(
                NetworkHeaders.Builder().apply {
                    avatarHeaders.forEach { (key, value) -> add(key, value) }
                }.build()
            )
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = avatarDescription,
        contentScale = ContentScale.Crop,
        modifier = avatarModifier,
        loading = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = colorScheme.surfaceContainerHighest
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        },
        error = {
            ChapterCommentAvatarPlaceholder(
                modifier = Modifier.fillMaxSize(),
                contentDescription = avatarDescription
            )
        }
    )
}

@Composable
private fun ChapterCommentAvatarPlaceholder(
    modifier: Modifier,
    contentDescription: String
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = colorScheme.secondaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                modifier = Modifier.size(24.dp),
                painter = painterResource(R.drawable.person_24px),
                contentDescription = contentDescription,
                tint = colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ChapterCommentTag(
    text: String,
    showWarningIcon: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (showWarningIcon) colorScheme.tertiaryContainer else colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showWarningIcon) {
                Icon(
                    modifier = Modifier.size(13.dp),
                    painter = painterResource(R.drawable.warning_24px),
                    contentDescription = null,
                    tint = colorScheme.onTertiaryContainer
                )
            }
            Text(
                text = text,
                style = typography.labelSmall,
                color = if (showWarningIcon) colorScheme.onTertiaryContainer else colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ChapterCommentQuotes(
    quotes: List<LinovelibCommentQuote>,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val shouldCollapse = quotes.size > 2 || quotes.sumOf { it.body.length } > 180
    val visibleQuotes = if (expanded || !shouldCollapse) quotes else quotes.take(2)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = colorScheme.surfaceContainerHighest
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visibleQuotes.forEach { quote ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            painter = painterResource(R.drawable.format_quote_24px),
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(
                                R.string.chapter_comments_quote,
                                quote.username.ifBlank {
                                    stringResource(R.string.chapter_comments_unknown_user)
                                },
                                quote.body
                            ),
                            style = typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = if (expanded || !shouldCollapse) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (shouldCollapse) {
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onToggle
            ) {
                Text(
                    stringResource(
                        if (expanded) R.string.chapter_comments_collapse_quotes
                        else R.string.chapter_comments_expand_quotes
                    )
                )
            }
        }
    }
}

@Composable
private fun ChapterCommentMetric(
    iconRes: Int,
    count: Int,
    contentDescription: String
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            modifier = Modifier.size(18.dp),
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = colorScheme.onSurfaceVariant
        )
        Text(
            text = count.toString(),
            style = typography.labelMedium,
            color = colorScheme.onSurfaceVariant
        )
    }
}
