package com.axion.ultimatefetcher.ui.apilab

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.axion.ultimatefetcher.domain.models.KeyValuePair
import com.axion.ultimatefetcher.ui.components.CyberGlassCard
import com.axion.ultimatefetcher.ui.components.EmptyStateCard
import com.axion.ultimatefetcher.ui.components.InfoCalloutBanner
import com.axion.ultimatefetcher.ui.components.KeyValueRow
import com.axion.ultimatefetcher.ui.components.MethodBadge
import com.axion.ultimatefetcher.ui.components.StatusCodeBadge
import com.axion.ultimatefetcher.ui.components.highlightJson
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils
import org.json.JSONArray

@Composable
fun ApiLabScreen(
    viewModel: ApiLabViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    var editorTab by remember { mutableIntStateOf(0) } // 0:Params, 1:Headers, 2:Body, 3:Vault, 4:History, 5:Collections
    var responseTab by remember { mutableIntStateOf(0) } // 0:Body, 1:Headers
    var showSaveCollectionDialog by remember { mutableStateOf(false) }
    var showAddCredentialDialog by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { raw ->
                viewModel.importCollectionsFromJson(raw)
            }
        }
    }

    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("API Lab", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            text = "REST Client • Encrypted Vault • Local Collections",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showSaveCollectionDialog = true },
                        modifier = Modifier.testTag("apilab_save_collection_btn")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Save to Collection", tint = cyber.cyan)
                    }
                }
            }

            if (state.feedbackMessage != null) {
                item {
                    InfoCalloutBanner(
                        title = "API Lab",
                        message = state.feedbackMessage.orEmpty(),
                        accentColor = cyber.cyan
                    )
                }
            }

            // Method + URL + Send Card
            item {
                CyberGlassCard(accentGlow = cs.primary) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        methods.forEach { method ->
                            FilterChip(
                                selected = state.draft.method == method,
                                onClick = { viewModel.updateMethod(method) },
                                label = { Text(method, fontFamily = JetBrainsMonoFamily, fontSize = 12.sp) },
                                modifier = Modifier.testTag("apilab_method_$method")
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = state.draft.url,
                        onValueChange = { viewModel.updateUrl(it) },
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://api.example.com/v1/resource") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("apilab_url_input"),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.executeRequest() },
                            enabled = !state.isExecuting,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("apilab_send_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (state.isExecuting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = cs.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Sending...")
                            } else {
                                Icon(Icons.Default.Send, contentDescription = "Send Request", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Send Request")
                            }
                        }
                        if (state.isExecuting) {
                            OutlinedButton(
                                onClick = { viewModel.cancelRequest() },
                                modifier = Modifier.height(48.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            // Request Editor Tabs
            item {
                val tabs = listOf("Params", "Headers", "Body", "KeyStore Auth", "History", "Collections")
                ScrollableTabRow(
                    selectedTabIndex = editorTab,
                    edgePadding = 0.dp,
                    containerColor = cs.surface,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp))
                ) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = editorTab == idx,
                            onClick = { editorTab = idx },
                            text = { Text(title, style = MaterialTheme.typography.labelLarge) }
                        )
                    }
                }
            }

            // Active Editor Panel
            when (editorTab) {
                0 -> {
                    item {
                        KeyValueEditorCard(
                            title = "Query Parameters",
                            items = state.draft.queryParams,
                            onAdd = { viewModel.addQueryParam() },
                            onUpdate = { i, kv -> viewModel.updateQueryParam(i, kv) },
                            onRemove = { viewModel.removeQueryParam(it) }
                        )
                    }
                }
                1 -> {
                    item {
                        KeyValueEditorCard(
                            title = "HTTP Headers",
                            items = state.draft.headers,
                            onAdd = { viewModel.addHeader() },
                            onUpdate = { i, kv -> viewModel.updateHeader(i, kv) },
                            onRemove = { viewModel.removeHeader(it) }
                        )
                    }
                }
                2 -> {
                    item {
                        CyberGlassCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Request Body", style = MaterialTheme.typography.titleMedium)
                                if (state.draft.bodyType == "JSON") {
                                    TextButton(onClick = { viewModel.formatRequestBodyJson() }) {
                                        Icon(Icons.Default.AutoFixHigh, contentDescription = "Format JSON", modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Format JSON")
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("NONE" to "None", "JSON" to "JSON", "FORM_URLENCODED" to "Form URL").forEach { (id, lbl) ->
                                    FilterChip(
                                        selected = state.draft.bodyType == id,
                                        onClick = { viewModel.updateBodyType(id) },
                                        label = { Text(lbl) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            when (state.draft.bodyType) {
                                "JSON" -> {
                                    OutlinedTextField(
                                        value = state.draft.bodyText,
                                        onValueChange = { viewModel.updateBodyText(it) },
                                        label = { Text("JSON Payload") },
                                        placeholder = { Text("{\n  \"key\": \"value\"\n}") },
                                        minLines = 5,
                                        maxLines = 12,
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp)
                                    )
                                }
                                "FORM_URLENCODED" -> {
                                    KeyValueEditorContent(
                                        items = state.draft.formFields,
                                        onAdd = { viewModel.addFormField() },
                                        onUpdate = { i, kv -> viewModel.updateFormField(i, kv) },
                                        onRemove = { viewModel.removeFormField(it) }
                                    )
                                }
                                else -> {
                                    Text(
                                        "No request body attached.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                3 -> {
                    item {
                        CyberGlassCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Encrypted Credential Vault", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "AES-256-GCM via Android KeyStore. Never sent to analytics.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(onClick = { showAddCredentialDialog = true }) {
                                    Icon(Icons.Default.Key, contentDescription = "Add Credential", modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Store")
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            if (state.savedCredentialNames.isEmpty()) {
                                Text(
                                    "No saved credentials yet. Store Bearer tokens or API keys securely on-device.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant
                                )
                            } else {
                                state.savedCredentialNames.forEach { name ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Row {
                                            TextButton(onClick = { viewModel.applySavedCredentialToHeaders(name) }) {
                                                Text("Apply Header")
                                            }
                                            IconButton(onClick = { viewModel.deleteSavedCredential(name) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = cs.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                4 -> {
                    if (history.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.History,
                                title = "No Request History",
                                description = "Execute an HTTP request above and it will be archived locally here."
                            )
                        }
                    } else {
                        items(history.take(25), key = { it.id }) { log ->
                            CyberGlassCard(onClick = {
                                viewModel.loadFromHistory(log)
                                editorTab = 0
                            }) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        MethodBadge(log.method)
                                        StatusCodeBadge(log.statusCode)
                                    }
                                    Text(
                                        "${log.durationMs}ms • ${FormatUtils.formatTime(log.timestamp)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cs.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = log.url,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                5 -> {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileUpload, contentDescription = "Import", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import JSON")
                            }
                            OutlinedButton(
                                onClick = {
                                    viewModel.exportCollectionsShareUri { uri ->
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(share, "Export API Collections"))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = "Export", modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export JSON")
                            }
                        }
                    }
                    if (collections.isEmpty()) {
                        item {
                            EmptyStateCard(
                                icon = Icons.Default.FolderOpen,
                                title = "No Saved Collections",
                                description = "Tap the bookmark icon at the top right to save requests into reusable collections."
                            )
                        }
                    } else {
                        items(collections, key = { it.id }) { col ->
                            val reqArray = remember(col.itemsJson) {
                                runCatching { JSONArray(col.itemsJson) }.getOrElse { JSONArray() }
                            }
                            CyberGlassCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(col.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${reqArray.length()} request(s) • ${col.description.ifBlank { "Local collection" }}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = cs.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteCollection(col.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Collection", tint = cs.error)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                for (i in 0 until reqArray.length()) {
                                    val obj = reqArray.optJSONObject(i) ?: continue
                                    val m = obj.optString("method", "GET")
                                    val u = obj.optString("url", "")
                                    val b = obj.optString("bodyText", "")
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            MethodBadge(m)
                                            Text(u, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        TextButton(onClick = {
                                            viewModel.loadDraftFromExternal(m, u, b)
                                            editorTab = 0
                                        }) {
                                            Text("Load")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Response Viewer
            state.lastResult?.let { result ->
                item {
                    CyberGlassCard(accentGlow = if (result.error == null && result.statusCode in 200..299) cyber.success else cs.error) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusCodeBadge(result.statusCode)
                                Text(
                                    "${result.durationMs} ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cyber.cyan
                                )
                                Text(
                                    FormatUtils.formatBytes(result.responseSizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant
                                )
                            }
                            Row {
                                if (result.isJson) {
                                    IconButton(onClick = { viewModel.togglePrettyPrint() }) {
                                        Icon(Icons.Default.Code, contentDescription = "Toggle Pretty JSON", tint = cyber.cyan)
                                    }
                                }
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(result.body))
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Body")
                                }
                            }
                        }

                        if (result.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(result.error, color = cs.error, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = responseTab == 0,
                                    onClick = { responseTab = 0 },
                                    label = { Text("Response Body") }
                                )
                                FilterChip(
                                    selected = responseTab == 1,
                                    onClick = { responseTab = 1 },
                                    label = { Text("Headers (${result.headers.size})") }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (responseTab == 0) {
                                val displayText = remember(result.body, state.prettyPrintResponse, result.isJson) {
                                    if (result.isJson && state.prettyPrintResponse) {
                                        FormatUtils.prettyPrintJson(result.body)
                                    } else result.body
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(cs.background.copy(alpha = 0.72f))
                                        .padding(12.dp)
                                ) {
                                    if (result.isJson) {
                                        Text(
                                            text = highlightJson(displayText.ifBlank { "(Empty body)" }),
                                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp)
                                        )
                                    } else {
                                        Text(
                                            text = displayText.ifBlank { "(Empty body)" },
                                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp)
                                        )
                                    }
                                }
                            } else {
                                result.headers.forEach { (k, v) ->
                                    KeyValueRow(k, v)
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showSaveCollectionDialog) {
        var colName by remember { mutableStateOf("Default Collection") }
        var colDesc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSaveCollectionDialog = false },
            title = { Text("Save Request to Collection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = colName,
                        onValueChange = { colName = it },
                        label = { Text("Collection Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = colDesc,
                        onValueChange = { colDesc = it },
                        label = { Text("Description (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveCurrentToCollection(colName, colDesc)
                    showSaveCollectionDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCollectionDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddCredentialDialog) {
        var credName by remember { mutableStateOf("") }
        var headerName by remember { mutableStateOf("Authorization") }
        var secretVal by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCredentialDialog = false },
            title = { Text("Store Encrypted API Credential") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = credName,
                        onValueChange = { credName = it },
                        label = { Text("Profile Name") },
                        placeholder = { Text("Staging Bearer Token") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it },
                        label = { Text("Header Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = secretVal,
                        onValueChange = { secretVal = it },
                        label = { Text("Secret Value (Encrypted via KeyStore)") },
                        placeholder = { Text("Bearer ...") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveEncryptedCredential(credName, headerName, secretVal)
                    showAddCredentialDialog = false
                }) { Text("Encrypt & Save") }
            },
            dismissButton = {
                TextButton(onClick = { showAddCredentialDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun KeyValueEditorCard(
    title: String,
    items: List<KeyValuePair>,
    onAdd: () -> Unit,
    onUpdate: (Int, KeyValuePair) -> Unit,
    onRemove: (Int) -> Unit
) {
    CyberGlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Row")
            }
        }
        KeyValueEditorContent(items, onAdd, onUpdate, onRemove)
    }
}

@Composable
private fun KeyValueEditorContent(
    items: List<KeyValuePair>,
    onAdd: () -> Unit,
    onUpdate: (Int, KeyValuePair) -> Unit,
    onRemove: (Int) -> Unit
) {
    val cs = MaterialTheme.colorScheme
    if (items.isEmpty()) {
        TextButton(onClick = onAdd) {
            Text("+ Add key-value pair", color = cs.secondary)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEachIndexed { idx, pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = pair.enabled,
                        onCheckedChange = { onUpdate(idx, pair.copy(enabled = it)) }
                    )
                    OutlinedTextField(
                        value = pair.key,
                        onValueChange = { onUpdate(idx, pair.copy(key = it)) },
                        placeholder = { Text("Key") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.labelMedium
                    )
                    OutlinedTextField(
                        value = pair.value,
                        onValueChange = { onUpdate(idx, pair.copy(value = it)) },
                        placeholder = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.labelMedium
                    )
                    IconButton(onClick = { onRemove(idx) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove", tint = cs.error)
                    }
                }
            }
        }
    }
}
