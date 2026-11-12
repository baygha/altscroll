package com.altscroll.app.ui.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altscroll.app.data.model.ModerationVerdict
import com.altscroll.app.data.repo.FeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewPostUiState(
    val title: String = "",
    val body: String = "",
    val sourceUrl: String = "",
    val submitting: Boolean = false,
    val verdict: ModerationVerdict? = null,
    val error: String? = null,
) {
    val titleValid: Boolean get() = title.trim().length in 3..200
    val bodyValid: Boolean get() = body.trim().length in 1..8000
    val wordCount: Int get() = body.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val canSubmit: Boolean get() = !submitting && titleValid && bodyValid
}

class NewPostViewModel(
    private val repo: FeedRepository = FeedRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(NewPostUiState())
    val state: StateFlow<NewPostUiState> = _state.asStateFlow()

    fun onTitle(v: String) = _state.update { it.copy(title = v, error = null) }
    fun onBody(v: String) = _state.update { it.copy(body = v, error = null) }
    fun onSourceUrl(v: String) = _state.update { it.copy(sourceUrl = v, error = null) }

    /**
     * Sends the draft to the moderation function. The verdict is authoritative:
     * the app never writes a post directly, so an approved status cannot be
     * forged by a modified client.
     */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null, verdict = null) }

        viewModelScope.launch {
            runCatching {
                repo.submitForModeration(
                    title = s.title,
                    body = s.body,
                    sourceUrl = s.sourceUrl.takeIf { it.isNotBlank() },
                )
            }
                .onSuccess { verdict ->
                    if (verdict.error != null) {
                        _state.update { it.copy(submitting = false, error = verdict.error) }
                    } else {
                        _state.update { it.copy(submitting = false, verdict = verdict) }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = e.message?.takeIf(String::isNotBlank)
                                ?: "Could not reach the moderation service.",
                        )
                    }
                }
        }
    }

    /** After an approved post, clear the form so the next draft starts clean. */
    fun reset() { _state.value = NewPostUiState() }

    fun dismissVerdict() = _state.update { it.copy(verdict = null) }
    fun dismissError() = _state.update { it.copy(error = null) }
}
