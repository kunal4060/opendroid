package com.opendroid.ai.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.rag.KnowledgeBaseRepository
import com.opendroid.ai.core.rag.RagAnswerResult
import com.opendroid.ai.core.rag.RagDocument
import com.opendroid.ai.core.rag.RetrievedChunk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One ask-box exchange, kept in memory for the screen's lifetime. */
data class KnowledgeBaseAnswerUi(
    val question: String,
    val text: String,
    val citations: List<RetrievedChunk>,
    /** True when retrieval found nothing and no model was consulted. */
    val noMatch: Boolean
)

@HiltViewModel
class KnowledgeBaseViewModel @Inject constructor(
    private val repository: KnowledgeBaseRepository
) : ViewModel() {

    val documents: StateFlow<List<RagDocument>> = repository.documents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val indexingProgress: StateFlow<Map<String, Float>> = repository.indexingProgress

    private val _searchMode = MutableStateFlow("")
    val searchMode: StateFlow<String> = _searchMode.asStateFlow()

    private val _question = MutableStateFlow("")
    val question: StateFlow<String> = _question.asStateFlow()

    private val _isAnswering = MutableStateFlow(false)
    val isAnswering: StateFlow<Boolean> = _isAnswering.asStateFlow()

    private val _answer = MutableStateFlow<KnowledgeBaseAnswerUi?>(null)
    val answer: StateFlow<KnowledgeBaseAnswerUi?> = _answer.asStateFlow()

    private val _askError = MutableStateFlow<String?>(null)
    val askError: StateFlow<String?> = _askError.asStateFlow()

    init {
        viewModelScope.launch {
            repository.refresh()
            _searchMode.value = repository.searchModeDescription()
        }
    }

    fun onQuestionChange(value: String) {
        _question.value = value
    }

    /** Imports each picked document; progress and status land in [documents]. */
    fun importDocuments(uris: List<Uri>) {
        for (uri in uris) {
            viewModelScope.launch {
                repository.importDocument(uri)
                _searchMode.value = repository.searchModeDescription()
            }
        }
    }

    fun deleteDocument(documentId: String) {
        viewModelScope.launch { repository.deleteDocument(documentId) }
    }

    fun ask() {
        val asked = _question.value.trim()
        if (asked.isEmpty() || _isAnswering.value) return
        _isAnswering.value = true
        _askError.value = null
        viewModelScope.launch {
            try {
                when (val result = repository.answer(asked)) {
                    is RagAnswerResult.Answered -> _answer.value = KnowledgeBaseAnswerUi(
                        question = asked,
                        text = result.text,
                        citations = result.citations,
                        noMatch = false
                    )
                    is RagAnswerResult.NoMatch -> _answer.value = KnowledgeBaseAnswerUi(
                        question = asked,
                        text = "I couldn't find that in your documents. " +
                            "Nothing in the knowledge base matches the question — " +
                            "try different words, or import the document that covers it.",
                        citations = emptyList(),
                        noMatch = true
                    )
                }
            } catch (e: Exception) {
                _askError.value = e.message ?: "Couldn't answer from your documents."
            } finally {
                _isAnswering.value = false
            }
        }
    }
}
