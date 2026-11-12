package com.altscroll.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.altscroll.app.data.model.FeedPost
import com.altscroll.app.ui.common.EmptyState
import com.altscroll.app.ui.common.LoadingBox
import com.altscroll.app.ui.common.shortTimestamp
import com.altscroll.app.ui.theme.LocalThemeState

/**
 * Full-screen snapping feed. One card per viewport, swipe up for the next, with
 * the whole post readable in place.
 *
 * The body carries a scroll container that only engages when the text actually
 * overflows. A post that fits consumes no drag, so the swipe gesture reaches
 * the pager untouched and scrolling stays as smooth as a plain pager; a long
 * post scrolls internally first, then hands the pager the leftover drag.
 */
@Composable
fun FeedScreen(
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { state.posts.size })
    val theme = LocalThemeState.current

    // Drive prefetch from the settled page rather than every scroll frame.
    LaunchedEffect(pagerState, state.posts.size) {
        snapshotFlow { pagerState.settledPage }
            .collect { viewModel.onPageSettled(it) }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.refreshing && state.posts.isEmpty() -> LoadingBox()

            state.posts.isEmpty() && state.error != null -> EmptyState(
                title = "Couldn't load the feed",
                detail = state.error,
                actionLabel = "Retry",
                onAction = viewModel::refresh,
            )

            state.isEmpty -> EmptyState(
                title = "Nothing here yet",
                detail = "Approved posts will appear here. Write the first one.",
                actionLabel = "Reload",
                onAction = viewModel::refresh,
            )

            else -> VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = { index -> state.posts[index].id },
            ) { page ->
                val post = state.posts[page]
                PostCard(
                    post = post,
                    onToggleLike = { viewModel.toggleLike(post.id) },
                )
            }
        }

        // Theme switch, floated over the feed so it needs no settings screen.
        IconButton(
            onClick = theme.toggle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(end = 12.dp, top = 8.dp),
        ) {
            Icon(
                imageVector = if (theme.isDark) Icons.Filled.LightMode
                else Icons.Filled.DarkMode,
                contentDescription = if (theme.isDark) "Switch to light theme"
                else "Switch to dark theme",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.loading && state.posts.isNotEmpty()) {
            CircularProgressIndicator(
                Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun TopicChip(topic: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    ) {
        Text(
            topic.uppercase(),
            Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun Byline(post: FeedPost, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val avatar = post.authorAvatarUrl
            if (avatar != null) {
                AsyncImage(avatar, null, Modifier.fillMaxSize())
            } else {
                Text(
                    post.byline.drop(1).take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            post.byline,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LikeButton(post: FeedPost, onToggleLike: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            post.likeCount.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onToggleLike) {
            Icon(
                imageVector = if (post.likedByMe) Icons.Filled.Favorite
                else Icons.Outlined.FavoriteBorder,
                contentDescription = if (post.likedByMe) "Unlike" else "Like",
                tint = if (post.likedByMe) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostCard(
    post: FeedPost,
    onToggleLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Card(
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    post.topic?.let {
                        TopicChip(it)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(
                        shortTimestamp(post.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // A scroll container always wins the vertical drag from the
                // pager underneath it, which made the middle of the card feel
                // different from the edges. `enabled` is tied to whether the
                // text actually overflows: maxValue is 0 when it fits, so a
                // short post hands the swipe straight to the pager and only a
                // genuinely long one scrolls in place.
                val bodyScroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(bodyScroll, enabled = bodyScroll.maxValue > 0),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    post.imageUrl?.let { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }

                    val title = post.title
                    if (!title.isNullOrBlank()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    // Imported library posts carry no title, so the fact itself
                    // is set at headline size and does the job of one.
                    Text(
                        post.body,
                        style = if (title.isNullOrBlank())
                            MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    )

                    post.sourceUrl?.let { src ->
                        Text(
                            "Source: " + src,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Byline(post)
                    LikeButton(post, onToggleLike)
                }
            }
        }
    }
}
