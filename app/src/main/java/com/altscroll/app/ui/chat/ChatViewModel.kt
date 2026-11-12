package com.altscroll.app.ui.chat

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altscroll.app.data.Speaker
import com.altscroll.app.data.VoiceRecorder
import com.altscroll.app.data.model.Message
import com.altscroll.app.data.model.Profile
import com.altscroll.app.data.repo.AuthRepository
import com.altscroll.app.data.repo.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val other: Profile? = null,
    val draft: String = "",
    val loading: Boolean = true,
    val sending: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    val speakReplies: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val myUserId: String? = null,
) {
    val canSend: Boolean get() = !sending && draft.isNotBlank()
    val busy: Boolean get() = recording || transcribing
}

class ChatViewModel(
    private val conversationId: String,
    appContext: Context,
    private val chat: ChatRepository = ChatRepository(),
    private val auth: AuthRepository = AuthRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState(myUserId = auth.currentUserId()))
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val recorder = VoiceRecorder(appContext)
    private val speaker = Speaker(appContext)

    /** Guards against reading the whole backlog aloud when the screen opens. */
    private var historyLoaded = false

    init {
        loadHistory()
        observeIncoming()
        pollForNewMessages()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            runCatching {
                val history = chat.messages(conversationId)
                val other = chat.participantProfile(conversationId)
                history to other
            }
                .onSuccess { (history, other) ->
                    _state.update {
                        it.copy(messages = history, other = other, loading = false)
                    }
                    historyLoaded = true
                    markRead()
                }
                .onFailure { e ->
                    historyLoaded = true
                    _state.update {
                        it.copy(
                            loading = false,
                            error = e.message?.takeIf(String::isNotBlank)
                                ?: "Could not load this conversation.",
                        )
                    }
                }
        }
    }

    /**
     * Fast path. The realtime socket delivers new rows within a few hundred
     * milliseconds when it is connected, but it can silently fail to attach -
     * so it is an optimisation here, never the only way a message arrives.
     */
    private fun observeIncoming() {
        viewModelScope.launch {
            runCatching {
                chat.newMessages(conversationId).collect { message -> merge(listOf(message)) }
            }.onFailure {
                // Deliberately silent: the poll below covers this, and an error
                // banner about websockets would mean nothing to the reader.
                android.util.Log.w("ChatViewModel", "realtime unavailable, polling instead", it)
            }
        }
    }

    /**
     * Safety net, and the thing that actually makes Peter's replies show up if
     * the socket is not delivering. Cheap: one indexed query on a single
     * conversation, only while this screen is on screen.
     */
    private fun pollForNewMessages() {
        viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                runCatching { chat.messages(conversationId) }
                    .onSuccess { merge(it) }
            }
        }
    }

    /** Adds anything not already held, keeping the list in send order. */
    private fun merge(incoming: List<Message>) {
        if (incoming.isEmpty()) return
        var spokenCandidate: Message? = null
        var addedFromOther = false

        _state.update { current ->
            val known = current.messages.mapTo(HashSet()) { it.id }
            val fresh = incoming.filterNot { it.id in known }
            if (fresh.isEmpty()) return@update current

            val fromOther = fresh.filter { it.senderId != current.myUserId }
            addedFromOther = fromOther.isNotEmpty()
            if (current.speakReplies && historyLoaded) {
                spokenCandidate = fromOther.maxByOrNull { it.createdAt }
            }
            current.copy(messages = (current.messages + fresh).sortedBy { it.createdAt })
        }

        spokenCandidate?.let { speaker.speak(it.content) }
        if (addedFromOther) markRead()
    }

    fun onDraft(v: String) = _state.update { it.copy(draft = v) }

    fun toggleSpeakReplies() {
        val next = !_state.value.speakReplies
        if (!next) speaker.stop()
        _state.update { it.copy(speakReplies = next) }
    }

    fun send() {
        val s = _state.value
        if (!s.canSend) return
        val text = s.draft
        _state.update { it.copy(draft = "", sending = true) }

        viewModelScope.launch {
            runCatching { chat.send(conversationId, text) }
                .onSuccess { sent ->
                    // Show it straight away rather than waiting for the socket.
                    merge(listOf(sent))
                    _state.update { it.copy(sending = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            sending = false,
                            draft = text, // give the text back rather than losing it
                            error = e.message?.takeIf(String::isNotBlank)
                                ?: "Message failed to send.",
                        )
                    }
                }
        }
    }

    // ---------------------------------------------------------------- voice

    fun startRecording() {
        if (_state.value.busy) return
        speaker.stop() // do not record Peter talking over the user
        if (recorder.start()) {
            _state.update { it.copy(recording = true, error = null, notice = null) }
        } else {
            _state.update { it.copy(error = "Could not start recording.") }
        }
    }

    /**
     * Called on release. Speaking turns itself on here: someone who just talked
     * to Peter almost certainly wants to hear the answer rather than read it.
     */
    fun stopRecordingAndSend() {
        if (!_state.value.recording) return
        _state.update { it.copy(recording = false, transcribing = true, speakReplies = true) }

        viewModelScope.launch {
            val encoded = withContext(Dispatchers.IO) { recorder.stopAndEncode() }
            if (encoded == null) {
                _state.update {
                    it.copy(transcribing = false, notice = "Too short - hold the mic while you talk.")
                }
                return@launch
            }

            runCatching { chat.sendVoiceNote(conversationId, encoded) }
                .onSuccess { result ->
                    when {
                        result.error != null ->
                            _state.update { it.copy(transcribing = false, error = result.error) }
                        result.message != null -> {
                            merge(listOf(result.message))
                            _state.update { it.copy(transcribing = false) }
                        }
                        else -> _state.update {
                            it.copy(transcribing = false, notice = "Didn't catch that - try again.")
                        }
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            transcribing = false,
                            error = e.message?.takeIf(String::isNotBlank)
                                ?: "Could not send that voice note.",
                        )
                    }
                }
        }
    }

    fun cancelRecording() {
        if (!_state.value.recording) return
        recorder.cancel()
        _state.update { it.copy(recording = false) }
    }

    private fun markRead() {
        viewModelScope.launch { runCatching { chat.markRead(conversationId) } }
    }

    fun dismissError() = _state.update { it.copy(error = null, notice = null) }

    override fun onCleared() {
        recorder.cancel()
        speaker.release()
        super.onCleared()
    }
}
