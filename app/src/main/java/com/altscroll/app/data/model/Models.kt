package com.altscroll.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val bio: String? = null,
    @SerialName("is_bot") val isBot: Boolean = false,
) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

/** One row of [com.altscroll.app.data.repo.FeedRepository.page], i.e. `feed_page()`. */
@Serializable
data class FeedPost(
    val id: String,
    /** Null for imported library posts, which are standalone facts with no headline. */
    val title: String? = null,
    val body: String,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val topic: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("created_at") val createdAt: String,
    @SerialName("author_id") val authorId: String? = null,
    @SerialName("author_username") val authorUsername: String? = null,
    @SerialName("author_avatar_url") val authorAvatarUrl: String? = null,
    @SerialName("liked_by_me") val likedByMe: Boolean = false,
) {
    /** Curated/imported rows have no author. */
    val isCurated: Boolean get() = authorId == null
    val byline: String get() = authorUsername?.let { "@$it" } ?: "AltScroll Library"
}

/** A post written by the signed-in user, including pending/rejected ones. */
@Serializable
data class OwnPost(
    val id: String,
    val title: String,
    val status: String,
    @SerialName("moderation_reason") val moderationReason: String? = null,
    @SerialName("moderation_score") val moderationScore: Float? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class Conversation(
    val id: String,
    @SerialName("is_group") val isGroup: Boolean = false,
    val title: String? = null,
    @SerialName("last_message_at") val lastMessageAt: String,
)

/** A conversation joined with its other participant and latest message. */
data class ConversationPreview(
    val conversationId: String,
    val otherUser: Profile?,
    val lastMessage: String?,
    val lastMessageAt: String,
    val unread: Boolean,
) {
    val title: String get() = otherUser?.label ?: "Conversation"
}

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_id") val senderId: String,
    val content: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ParticipantRow(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("last_read_at") val lastReadAt: String? = null,
)

/** Response of the `moderate-post` edge function. */
@Serializable
data class ModerationVerdict(
    @SerialName("post_id") val postId: String? = null,
    val status: String? = null,
    val approved: Boolean = false,
    val score: Float = 0f,
    val reason: String = "",
    val topic: String? = null,
    val tags: List<String> = emptyList(),
    val model: String? = null,
    val error: String? = null,
)

/** Response of the `transcribe` edge function. */
@Serializable
data class TranscriptionResult(
    val transcript: String = "",
    val message: Message? = null,
    val skipped: String? = null,
    val error: String? = null,
)
