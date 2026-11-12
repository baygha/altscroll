package com.altscroll.app.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altscroll.app.data.model.FeedPost
import com.altscroll.app.data.repo.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FeedUiState(
    val posts: List<FeedPost> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val endReached: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = posts.isEmpty() && !loading && error == null
}

class FeedViewModel(
    private val repo: FeedRepository = FeedRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState())
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** Start fetching the next page once the user is this close to the end. */
    private val prefetchDistance = 3
    private val pageSize = 10

    init { refresh() }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.page(pageSize = pageSize) }
                .onSuccess { page ->
                    _state.update {
                        it.copy(
                            posts = page,
                            refreshing = false,
                            endReached = page.size < pageSize,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(refreshing = false, error = e.readableMessage())
                    }
                }
        }
    }

    /** Called as the pager settles on [index]; loads ahead so scrolling never stalls. */
    fun onPageSettled(index: Int) {
        val s = _state.value
        if (s.loading || s.endReached || s.posts.isEmpty()) return
        if (index < s.posts.size - prefetchDistance) return
        loadMore()
    }

    private fun loadMore() {
        val last = _state.value.posts.lastOrNull() ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching {
                repo.page(
                    beforeTs = last.createdAt,
                    beforeId = last.id,
                    pageSize = pageSize,
                )
            }
                .onSuccess { page ->
                    _state.update { current ->
                        val known = current.posts.mapTo(HashSet()) { it.id }
                        current.copy(
                            posts = current.posts + page.filterNot { it.id in known },
                            loading = false,
                            endReached = page.size < pageSize,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.readableMessage()) }
                }
        }
    }

    /** Optimistic: flip locally, then reconcile if the write fails. */
    fun toggleLike(postId: String) {
        val post = _state.value.posts.firstOrNull { it.id == postId } ?: return
        val target = !post.likedByMe
        applyLike(postId, target)
        viewModelScope.launch {
            runCatching { repo.setLiked(postId, target) }
                .onFailure {
                    applyLike(postId, !target)
                    _state.update { it.copy(error = "Could not save that like.") }
                }
        }
    }

    private fun applyLike(postId: String, liked: Boolean) {
        _state.update { current ->
            current.copy(
                posts = current.posts.map { p ->
                    if (p.id != postId) p
                    else p.copy(
                        likedByMe = liked,
                        likeCount = (p.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                    )
                }
            )
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}

internal fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Check your connection."
