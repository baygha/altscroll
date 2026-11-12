package com.altscroll.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.altscroll.app.data.model.ConversationPreview
import com.altscroll.app.data.model.Profile
import com.altscroll.app.ui.common.AiBadge
import com.altscroll.app.ui.common.EmptyState
import com.altscroll.app.ui.common.LoadingBox
import com.altscroll.app.ui.common.shortTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openSearch,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New chat") },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingBox()

                state.error != null -> EmptyState(
                    title = "Couldn't load your chats",
                    detail = state.error,
                    actionLabel = "Retry",
                    onAction = viewModel::load,
                )

                state.conversations.isEmpty() -> EmptyState(
                    title = "No conversations yet",
                    detail = "Find someone to talk to and start a thread.",
                    actionLabel = "New chat",
                    onAction = viewModel::openSearch,
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.conversationId }) { conv ->
                        ConversationRow(conv) { onOpenConversation(conv.conversationId) }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        )
                    }
                }
            }
        }
    }

    if (state.searchOpen) {
        ModalBottomSheet(onDismissRequest = viewModel::closeSearch) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Start a conversation", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQuery,
                    label = { Text("Search by username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(12.dp))
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(state.searchResults, key = { it.id }) { profile ->
                        UserRow(profile) {
                            viewModel.startChat(profile.id, onOpenConversation)
                        }
                    }
                }
                if (state.searchResults.isEmpty() && !state.searching) {
                    Text(
                        "No users match that name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    conv: ConversationPreview,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conv.otherUser)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conv.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conv.unread) FontWeight.Bold else FontWeight.Medium,
                )
                if (conv.otherUser?.isBot == true) {
                    Spacer(Modifier.width(6.dp))
                    AiBadge()
                }
            }
            Text(
                conv.lastMessage ?: "No messages yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                shortTimestamp(conv.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conv.unread) {
                Spacer(Modifier.size(6.dp))
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
private fun UserRow(profile: Profile, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Avatar(profile)
        Spacer(Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.label, style = MaterialTheme.typography.titleSmall)
                if (profile.isBot) {
                    Spacer(Modifier.width(6.dp))
                    AiBadge()
                }
            }
            Text(
                "@" + profile.username,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Avatar(profile: Profile?) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val url = profile?.avatarUrl
        if (url != null) {
            AsyncImage(url, null, Modifier.fillMaxSize())
        } else {
            Text(
                (profile?.label ?: "?").take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
