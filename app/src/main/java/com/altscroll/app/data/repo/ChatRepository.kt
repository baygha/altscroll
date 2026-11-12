package com.altscroll.app.data.repo

import com.altscroll.app.data.SupabaseProvider
import com.altscroll.app.data.model.Conversation
import com.altscroll.app.data.model.ConversationPreview
import com.altscroll.app.data.model.Message
import com.altscroll.app.data.model.ParticipantRow
import com.altscroll.app.data.model.Profile
import com.altscroll.app.data.model.TranscriptionResult
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ChatRepository {

    private val supabase get() = SupabaseProvider.client

    private fun requireUserId(): String =
        supabase.auth.currentUserOrNull()?.id
            ?: error("Not signed in")

    /**
     * Inbox list. Supabase has no server-side join for "the other participant",
     * so this fans out three small queries and stitches them client-side —
     * cheaper than an RPC for the handful of conversations a user has.
     */
    suspend fun conversations(): List<ConversationPreview> {
        val me = requireUserId()

        val myRows = supabase.from("conversation_participants")
            .select { filter { eq("user_id", me) } }
            .decodeList<ParticipantRow>()
        if (myRows.isEmpty()) return emptyList()

        val convIds = myRows.map { it.conversationId }

        val convs = supabase.from("conversations")
            .select {
                filter { isIn("id", convIds) }
                order("last_message_at", Order.DESCENDING)
            }
            .decodeList<Conversation>()

        val others = supabase.from("conversation_participants")
            .select {
                filter {
                    isIn("conversation_id", convIds)
                    neq("user_id", me)
                }
            }
            .decodeList<ParticipantRow>()

        val otherIdByConv = others.associate { it.conversationId to it.userId }
        val profiles = if (otherIdByConv.isEmpty()) emptyList() else
            supabase.from("profiles")
                .select { filter { isIn("id", otherIdByConv.values.distinct()) } }
                .decodeList<Profile>()
        val profileById = profiles.associateBy { it.id }

        val lastReadByConv = myRows.associate { it.conversationId to (it.lastReadAt ?: "") }

        // One latest message per conversation.
        val latest = supabase.from("messages")
            .select {
                filter { isIn("conversation_id", convIds) }
                order("created_at", Order.DESCENDING)
                limit(convIds.size * 20L)
            }
            .decodeList<Message>()
        val lastByConv = latest.groupBy { it.conversationId }
            .mapValues { (_, msgs) -> msgs.maxByOrNull { it.createdAt } }

        return convs.map { conv ->
            val last = lastByConv[conv.id]
            ConversationPreview(
                conversationId = conv.id,
                otherUser = otherIdByConv[conv.id]?.let { profileById[it] },
                lastMessage = last?.content,
                lastMessageAt = conv.lastMessageAt,
                unread = last != null &&
                    last.senderId != me &&
                    last.createdAt > (lastReadByConv[conv.id] ?: ""),
            )
        }
    }

    /** Returns the existing 1:1 conversation with [otherUserId], or creates it. */
    suspend fun startDirectConversation(otherUserId: String): String {
        val result = supabase.postgrest.rpc(
            "start_direct_conversation",
            buildJsonObject { put("other_user", otherUserId) },
        )
        // The function returns a bare uuid, which arrives as a JSON string.
        return result.data.trim().trim('"')
    }

    suspend fun messages(conversationId: String, limit: Int = 100): List<Message> =
        supabase.from("messages")
            .select {
                filter { eq("conversation_id", conversationId) }
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }
            .decodeList<Message>()
            .reversed() // oldest first for display

    /**
     * Returns the stored row rather than Unit, so the sender sees their own
     * message the instant the write lands instead of waiting for it to come
     * back around over the realtime socket.
     */
    suspend fun send(conversationId: String, content: String): Message {
        val me = requireUserId()
        return supabase.from("messages").insert(
            buildJsonObject {
                put("conversation_id", conversationId)
                put("sender_id", me)
                put("content", content.trim())
            }
        ) { select() }.decodeSingle()
    }

    /**
     * Uploads a held-to-talk clip. The function transcribes it and inserts the
     * text as an ordinary message, so the bot-reply trigger fires exactly as it
     * would for typing.
     */
    suspend fun sendVoiceNote(
        conversationId: String,
        audioBase64: String,
        mimeType: String = "audio/mp4",
        language: String = java.util.Locale.getDefault().language,
    ): TranscriptionResult {
        val response = supabase.functions.invoke("transcribe") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("conversation_id", conversationId)
                    put("audio_base64", audioBase64)
                    put("mime_type", mimeType)
                    put("language", language)
                }
            )
        }
        return response.body()
    }

    suspend fun markRead(conversationId: String) {
        val me = requireUserId()
        supabase.from("conversation_participants").update(
            buildJsonObject { put("last_read_at", nowIso()) }
        ) {
            filter {
                eq("conversation_id", conversationId)
                eq("user_id", me)
            }
        }
    }

    suspend fun participantProfile(conversationId: String): Profile? {
        val me = requireUserId()
        val other = supabase.from("conversation_participants")
            .select(Columns.list("conversation_id", "user_id", "last_read_at")) {
                filter {
                    eq("conversation_id", conversationId)
                    neq("user_id", me)
                }
                limit(1)
            }
            .decodeList<ParticipantRow>()
            .firstOrNull() ?: return null

        return supabase.from("profiles")
            .select { filter { eq("id", other.userId) } }
            .decodeSingleOrNull()
    }

    /**
     * Live stream of new messages in a conversation. The channel is subscribed
     * when collection starts and torn down when the collector goes away, so a
     * screen leaving composition does not leak a websocket.
     */
    fun newMessages(conversationId: String): Flow<Message> {
        val channel = supabase.channel("conversation:$conversationId")
        val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        return changes
            .map { it.decodeRecord<Message>() }
            .onStart { channel.subscribe(blockUntilSubscribed = true) }
            .onCompletion { supabase.realtime.removeChannel(channel) }
    }

    private fun nowIso(): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
