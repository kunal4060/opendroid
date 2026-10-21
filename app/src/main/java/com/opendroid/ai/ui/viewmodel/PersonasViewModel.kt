package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.data.models.Persona
import com.opendroid.ai.data.models.PersonaKind
import com.opendroid.ai.data.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the persona library management screen (feature B6) and the
 * source of the persona flows ChatViewModel exposes to the chat header.
 * All persistence lives in [PersonaRepository]; this class only adapts it
 * to UI state and turns validation/storage failures into a one-shot message.
 */
@HiltViewModel
class PersonasViewModel @Inject constructor(
    private val personaRepository: PersonaRepository
) : ViewModel() {

    /** The whole library (personas + quick commands), in library order. */
    val personas: StateFlow<List<Persona>> = personaRepository.personas
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** sessionId → personaId bindings, used to show "used in N chats" per entry. */
    val sessionBindings: StateFlow<Map<String, String>> = personaRepository.sessionBindings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _userMessage = MutableStateFlow<String?>(null)

    /** One-shot result message (import outcome, validation failure); cleared by the UI. */
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun createPersona(name: String, prompt: String, kind: PersonaKind) {
        viewModelScope.launch {
            runCatching { personaRepository.createPersona(name, prompt, kind) }
                .onFailure { error -> _userMessage.value = error.message ?: "Could not save" }
        }
    }

    fun updatePersona(id: String, name: String, prompt: String, kind: PersonaKind) {
        viewModelScope.launch {
            runCatching { personaRepository.updatePersona(id, name, prompt, kind) }
                .onFailure { error -> _userMessage.value = error.message ?: "Could not save" }
        }
    }

    fun duplicatePersona(id: String) {
        viewModelScope.launch {
            personaRepository.duplicatePersona(id)
        }
    }

    /** Deletes an entry; the repository strips its chat bindings in the same edit. */
    fun deletePersona(id: String) {
        viewModelScope.launch {
            personaRepository.deletePersona(id)
        }
    }

    /** The export payload for the share sheet. */
    suspend fun exportJson(): String = personaRepository.exportJson()

    fun importJson(text: String) {
        viewModelScope.launch {
            runCatching { personaRepository.importJson(text) }
                .onSuccess { added ->
                    _userMessage.value = when (added) {
                        0 -> "Nothing new was imported."
                        1 -> "Imported 1 entry."
                        else -> "Imported $added entries."
                    }
                }
                .onFailure {
                    _userMessage.value = "That file could not be imported as a persona library."
                }
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }
}
