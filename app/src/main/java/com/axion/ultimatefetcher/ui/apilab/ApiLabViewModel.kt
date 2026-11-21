package com.axion.ultimatefetcher.ui.apilab

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.axion.ultimatefetcher.AppContainer
import com.axion.ultimatefetcher.data.database.entities.ApiRequestLog
import com.axion.ultimatefetcher.data.database.entities.SavedCollection
import com.axion.ultimatefetcher.domain.models.ApiExecutionResult
import com.axion.ultimatefetcher.domain.models.ApiRequestDraft
import com.axion.ultimatefetcher.domain.models.KeyValuePair
import com.axion.ultimatefetcher.utils.FormatUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ApiLabUiState(
    val draft: ApiRequestDraft = ApiRequestDraft(),
    val isExecuting: Boolean = false,
    val lastResult: ApiExecutionResult? = null,
    val prettyPrintResponse: Boolean = true,
    val savedCredentialNames: List<String> = emptyList(),
    val feedbackMessage: String? = null
)

class ApiLabViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ApiLabUiState(savedCredentialNames = container.credentialStore.listCredentialNames())
    )
    val uiState: StateFlow<ApiLabUiState> = _uiState.asStateFlow()

    val history: StateFlow<List<ApiRequestLog>> = container.repository.observeApiLabHistory(100)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val collections: StateFlow<List<SavedCollection>> = container.repository.observeCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateMethod(method: String) {
        _uiState.update { it.copy(draft = it.draft.copy(method = method)) }
    }

    fun updateUrl(url: String) {
        _uiState.update { it.copy(draft = it.draft.copy(url = url)) }
    }

    fun addQueryParam() {
        _uiState.update { it.copy(draft = it.draft.copy(queryParams = it.draft.queryParams + KeyValuePair())) }
    }

    fun updateQueryParam(index: Int, kv: KeyValuePair) {
        _uiState.update { s ->
            val list = s.draft.queryParams.toMutableList()
            if (index in list.indices) list[index] = kv
            s.copy(draft = s.draft.copy(queryParams = list))
        }
    }

    fun removeQueryParam(index: Int) {
        _uiState.update { s ->
            val list = s.draft.queryParams.toMutableList()
            if (index in list.indices) list.removeAt(index)
            s.copy(draft = s.draft.copy(queryParams = list))
        }
    }

    fun addHeader() {
        _uiState.update { it.copy(draft = it.draft.copy(headers = it.draft.headers + KeyValuePair())) }
    }

    fun updateHeader(index: Int, kv: KeyValuePair) {
        _uiState.update { s ->
            val list = s.draft.headers.toMutableList()
            if (index in list.indices) list[index] = kv
            s.copy(draft = s.draft.copy(headers = list))
        }
    }

    fun removeHeader(index: Int) {
        _uiState.update { s ->
            val list = s.draft.headers.toMutableList()
            if (index in list.indices) list.removeAt(index)
            s.copy(draft = s.draft.copy(headers = list))
        }
    }

    fun updateBodyType(bodyType: String) {
        _uiState.update { it.copy(draft = it.draft.copy(bodyType = bodyType)) }
    }

    fun updateBodyText(bodyText: String) {
        _uiState.update { it.copy(draft = it.draft.copy(bodyText = bodyText)) }
    }

    fun formatRequestBodyJson() {
        _uiState.update { s ->
            s.copy(draft = s.draft.copy(bodyText = FormatUtils.prettyPrintJson(s.draft.bodyText)))
        }
    }

    fun addFormField() {
        _uiState.update { it.copy(draft = it.draft.copy(formFields = it.draft.formFields + KeyValuePair())) }
    }

    fun updateFormField(index: Int, kv: KeyValuePair) {
        _uiState.update { s ->
            val list = s.draft.formFields.toMutableList()
            if (index in list.indices) list[index] = kv
            s.copy(draft = s.draft.copy(formFields = list))
        }
    }

    fun removeFormField(index: Int) {
        _uiState.update { s ->
            val list = s.draft.formFields.toMutableList()
            if (index in list.indices) list.removeAt(index)
            s.copy(draft = s.draft.copy(formFields = list))
        }
    }

    fun executeRequest() {
        if (_uiState.value.isExecuting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true) }
            val timeout = container.settingsDataStore.settingsFlow.first().defaultTimeoutSeconds
            val result = container.apiLabClient.execute(_uiState.value.draft, timeout)
            _uiState.update { it.copy(isExecuting = false, lastResult = result) }
        }
    }

    fun cancelRequest() {
        container.apiLabClient.cancelActive()
        _uiState.update { it.copy(isExecuting = false) }
        postFeedback("Request cancelled.")
    }

    fun togglePrettyPrint() {
        _uiState.update { it.copy(prettyPrintResponse = !it.prettyPrintResponse) }
    }

    fun saveEncryptedCredential(name: String, headerName: String, secretValue: String) {
        val ok = container.credentialStore.saveCredential(name, headerName, secretValue)
        _uiState.update { it.copy(savedCredentialNames = container.credentialStore.listCredentialNames()) }
        postFeedback(if (ok) "Credential encrypted and saved in Android KeyStore." else "Enter profile name, header, and secret value.")
    }

    fun applySavedCredentialToHeaders(name: String) {
        val pair = container.credentialStore.loadCredential(name)
        if (pair == null) {
            postFeedback("Unable to decrypt credential '$name'.")
            return
        }
        val (headerName, secretVal) = pair
        _uiState.update { s ->
            val updated = s.draft.headers.filterNot { it.key.equals(headerName, ignoreCase = true) } +
                KeyValuePair(headerName, secretVal, true)
            s.copy(draft = s.draft.copy(headers = updated))
        }
        postFeedback("Applied encrypted '$headerName' header from KeyStore.")
    }

    fun deleteSavedCredential(name: String) {
        container.credentialStore.deleteCredential(name)
        _uiState.update { it.copy(savedCredentialNames = container.credentialStore.listCredentialNames()) }
        postFeedback("Removed credential '$name'.")
    }

    fun loadFromHistory(log: ApiRequestLog) {
        _uiState.update { s ->
            s.copy(
                draft = s.draft.copy(
                    method = log.method,
                    url = log.url,
                    bodyType = if (log.requestBodyPreview.isNotBlank()) "JSON" else "NONE",
                    bodyText = log.requestBodyPreview
                )
            )
        }
        postFeedback("Loaded request from history.")
    }

    fun loadDraftFromExternal(method: String, url: String, body: String) {
        _uiState.update { s ->
            s.copy(
                draft = s.draft.copy(
                    method = method.uppercase(),
                    url = url,
                    bodyType = if (body.isNotBlank()) "JSON" else "NONE",
                    bodyText = body
                )
            )
        }
        postFeedback("Loaded $method endpoint into API Lab.")
    }

    fun saveCurrentToCollection(name: String, description: String) {
        viewModelScope.launch {
            container.repository.saveRequestToCollection(name, description, _uiState.value.draft)
            postFeedback("Saved request to '$name'.")
        }
    }

    fun deleteCollection(id: Long) {
        viewModelScope.launch {
            container.repository.deleteCollection(id)
            postFeedback("Deleted collection.")
        }
    }

    fun importCollectionsFromJson(rawJson: String) {
        viewModelScope.launch {
            val count = runCatching { container.repository.importCollectionsJson(rawJson) }.getOrDefault(-1)
            postFeedback(if (count >= 0) "Imported $count collection(s)." else "Invalid collections JSON file.")
        }
    }

    fun exportCollectionsShareUri(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            val json = container.repository.exportCollectionsJson()
            val uri = container.repository.writeTempExportAndGetShareUri(
                "ultimatefetcher_collections_${System.currentTimeMillis()}.json",
                json
            )
            onReady(uri)
        }
    }

    private fun postFeedback(msg: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(feedbackMessage = msg) }
            delay(3000L)
            _uiState.update { if (it.feedbackMessage == msg) it.copy(feedbackMessage = null) else it }
        }
    }
}
