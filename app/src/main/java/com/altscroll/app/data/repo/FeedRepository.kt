package com.altscroll.app.data.repo

import com.altscroll.app.data.SupabaseProvider
import com.altscroll.app.data.model.FeedPost
import com.altscroll.app.data.model.ModerationVerdict
import com.altscroll.app.data.model.OwnPost
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FeedRepository {

    private val supabase get() = SupabaseProvider.client

    /**
     * Keyset pagination on the (createdAt, id) pair of the last post held.
     * The pair matters: the CSV import gave hundreds of rows the same
     * timestamp, and a timestamp-only cursor skips or stalls on those ties.
     * Keyset beats OFFSET here because the feed is append-heavy — a new post
     * arriving mid-scroll would shift every offset and duplicate a row.
     */
    suspend fun page(
        beforeTs: String? = null,
        beforeId: String? = null,
        pageSize: Int = 10,
    ): List<FeedPost> {
        val params = buildJsonObject {
            if (beforeTs == null) put("before_ts", JsonNull) else put("before_ts", beforeTs)
            if (beforeId == null) put("before_id", JsonNull) else put("before_id", beforeId)
            put("page_size", pageSize)
        }
        return supabase.postgrest.rpc("feed_page", params).decodeList()
    }

    suspend fun setLiked(postId: String, liked: Boolean) {
        val userId = supabase.auth.currentUserOrNull()?.id ?: return
        if (liked) {
            supabase.from("post_likes").insert(
                buildJsonObject {
                    put("post_id", postId)
                    put("user_id", userId)
                }
            )
        } else {
            supabase.from("post_likes").delete {
                filter {
                    eq("post_id", postId)
                    eq("user_id", userId)
                }
            }
        }
    }

    /** Posts written by the signed-in user, any status. */
    suspend fun myPosts(): List<OwnPost> {
        val userId = supabase.auth.currentUserOrNull()?.id ?: return emptyList()
        return supabase.from("posts")
            .select(io.github.jan.supabase.postgrest.query.Columns.list(
                "id", "title", "status", "moderation_reason", "moderation_score", "created_at"
            )) {
                filter { eq("author_id", userId) }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(50)
            }
            .decodeList()
    }

    /**
     * Submits a draft to the `moderate-post` edge function, which classifies it
     * and writes it with the service role. The client never decides the status.
     */
    suspend fun submitForModeration(
        title: String,
        body: String,
        sourceUrl: String?,
        imageUrl: String? = null,
    ): ModerationVerdict {
        val response = supabase.functions.invoke("moderate-post") {
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("title", title.trim())
                    put("body", body.trim())
                    if (sourceUrl.isNullOrBlank()) put("source_url", JsonNull)
                    else put("source_url", sourceUrl.trim())
                    if (imageUrl.isNullOrBlank()) put("image_url", JsonNull)
                    else put("image_url", imageUrl.trim())
                }
            )
        }
        return response.body()
    }
}
