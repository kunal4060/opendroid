package com.opendroid.ai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opendroid.ai.core.templates.TemplateExport
import com.opendroid.ai.core.templates.TemplateFormat
import com.opendroid.ai.core.templates.TemplateImportPreview
import com.opendroid.ai.core.templates.TemplateShareService
import com.opendroid.ai.data.models.Macro
import com.opendroid.ai.data.models.Plan
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for C6 template transfer: the pending import preview plus a
 * one-shot message for outcomes (import done, export saved/failed). File IO
 * itself lives in the screens via `TemplateFileIo`; this ViewModel owns only
 * the validate → preview → confirm flow against [TemplateShareService].
 */
@HiltViewModel
class TemplateShareViewModel @Inject constructor(
    private val service: TemplateShareService
) : ViewModel() {

    private val _importPreview = MutableStateFlow<TemplateImportPreview?>(null)
    val importPreview: StateFlow<TemplateImportPreview?> = _importPreview

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun macroExport(macro: Macro): TemplateExport = service.buildMacroExport(macro)

    fun planExport(plan: Plan): TemplateExport = service.buildPlanExport(plan)

    /** A file was picked: validate it and show the preview (writes nothing). */
    fun beginImport(rawFileText: String) {
        viewModelScope.launch {
            _importPreview.value = service.previewImport(rawFileText)
        }
    }

    fun confirmImport() {
        val preview = _importPreview.value ?: return
        viewModelScope.launch {
            try {
                val imported = service.confirmImport(preview)
                _importPreview.value = null
                _message.value = if (imported.kind == TemplateFormat.KIND_MACRO) {
                    "Imported macro '${imported.name}'. It starts switched off — " +
                        "enable it from the Macros list when you're ready to run it."
                } else {
                    "Imported plan '${imported.name}' — find it in the Plan history."
                }
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun dismissImport() {
        _importPreview.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun clearMessage() {
        _message.value = null
    }
}
