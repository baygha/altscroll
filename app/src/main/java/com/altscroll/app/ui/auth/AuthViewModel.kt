package com.altscroll.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altscroll.app.data.repo.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode { SignIn, SignUp }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val username: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSubmit: Boolean
        get() = !submitting &&
            email.contains('@') &&
            password.length >= 6 &&
            (mode == AuthMode.SignIn || username.trim().length >= 3)
}

class AuthViewModel(
    private val repo: AuthRepository = AuthRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }

    fun toggleMode() = _state.update {
        it.copy(
            mode = if (it.mode == AuthMode.SignIn) AuthMode.SignUp else AuthMode.SignIn,
            error = null,
            notice = null,
        )
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(submitting = true, error = null, notice = null) }

        viewModelScope.launch {
            val result = runCatching {
                if (s.mode == AuthMode.SignIn) {
                    repo.signIn(s.email, s.password)
                    null
                } else {
                    repo.signUp(s.email, s.password, s.username)
                    // With email confirmation on, no session exists yet.
                    "Account created. Check your inbox to confirm, then sign in."
                }
            }

            result
                .onSuccess { notice ->
                    _state.update {
                        it.copy(
                            submitting = false,
                            notice = notice,
                            mode = if (notice != null) AuthMode.SignIn else it.mode,
                            password = if (notice != null) "" else it.password,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(submitting = false, error = friendly(e)) }
                }
        }
    }

    private fun friendly(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("Invalid login", ignoreCase = true) ->
                "That email and password don't match an account."
            raw.contains("already registered", ignoreCase = true) ||
                raw.contains("already been registered", ignoreCase = true) ->
                "That email is already registered. Try signing in."
            raw.contains("duplicate key", ignoreCase = true) &&
                raw.contains("username", ignoreCase = true) ->
                "That username is taken. Pick another."
            raw.contains("Email not confirmed", ignoreCase = true) ->
                "Confirm your email address first, then sign in."
            raw.contains("not configured", ignoreCase = true) -> raw
            raw.isBlank() -> "Something went wrong. Check your connection."
            else -> raw
        }
    }
}
