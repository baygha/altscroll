package com.altscroll.app.data.repo

import com.altscroll.app.data.SupabaseProvider
import com.altscroll.app.data.model.Profile
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AuthRepository {

    private val supabase get() = SupabaseProvider.client

    val sessionStatus: Flow<SessionStatus> get() = supabase.auth.sessionStatus

    val isSignedIn: Flow<Boolean>
        get() = sessionStatus.map { it is SessionStatus.Authenticated }

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    suspend fun signUp(email: String, password: String, username: String) {
        supabase.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
            data = buildJsonObject {
                put("username", username.trim())
                put("display_name", username.trim())
            }
        }
    }

    suspend fun signIn(email: String, password: String) {
        supabase.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun myProfile(): Profile? {
        val id = currentUserId() ?: return null
        return supabase.from("profiles")
            .select { filter { eq("id", id) } }
            .decodeSingleOrNull<Profile>()
    }

    /** User search for starting a chat. Excludes the signed-in user. */
    suspend fun searchUsers(query: String, limit: Int = 25): List<Profile> {
        val me = currentUserId()
        val trimmed = query.trim()
        return supabase.from("profiles")
            .select {
                filter {
                    if (trimmed.isNotEmpty()) ilike("username", "%$trimmed%")
                    if (me != null) neq("id", me)
                }
                order("username", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
                limit(limit.toLong())
            }
            .decodeList()
    }
}
