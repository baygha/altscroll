package com.altscroll.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altscroll.app.data.model.ConversationPreview
import com.altscroll.app.data.model.Profile
import com.altscroll.app.data.repo.AuthRepository
import com.altscroll.app.data.repo.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConversationsUiState(
    val conversations: List<ConversationPreview> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val searchResults: List<Profile> = emptyList(),
    val searching: Boolean = false,
    val searchOpen: Boolean = false,
)

class ConversationsViewModel(
    private val chat: ChatRepository = ChatRepository(),
    private val auth: AuthRepository = AuthRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(ConversationsUiState())
    val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init { load() }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { chat.conversations() }
                .onSuccess { list ->
                    _state.update { it.copy(conversations = list, loading = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message?.takeIf(String::isNotBlank)
                                ?: "Could not load your chats.",
                        )
                    }
                }
        }
    }

    fun openSearch() = _state.update { it.copy(searchOpen = true) }

    fun closeSearch() = _state.update {
        it.copy(searchOpen = false, searchQuery = "", searchResults = emptyList())
    }

    /** Debounced so typing a name is one query, not one per keystroke. */
    fun onSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query, searching = true) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            runCatching { auth.searchUsers(query) }
                .onSuccess { results ->
                    _state.update { it.copy(searchResults = results, searching = false) }
                }
                .onFailure {
                    _state.update { it.copy(searching = false) }
                }
        }
    }

    /** Opens (or creates) the 1:1 thread, then hands the id back for navigation. */
    fun startChat(userId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { chat.startDirectConversation(userId) }
                .onSuccess { conversationId ->
                    closeSearch()
                    onReady(conversationId)
                    load()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(error = e.message ?: "Could not start that chat.")
                    }
                }
        }
    }
}
