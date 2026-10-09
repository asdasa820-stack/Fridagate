package com.hackpuntes.fridagate.ui.scripts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

@Composable
fun ScriptsScreen() {
    val context = LocalContext.current
    val viewModel: ScriptsViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ScriptsViewModel(context) as T
        }
    )

    val openFile        by viewModel.openFile.collectAsState()
    val showNewDialog   by viewModel.showNewDialog.collectAsState()
    val showRenameFile  by viewModel.showRenameDialog.collectAsState()

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showNewDialog) {
        NameDialog(
            title       = "New Script",
            placeholder = "my_hook",
            confirmText = "Create",
            onConfirm   = { viewModel.createScript(it) },
            onDismiss   = { viewModel.dismissNewDialog() }
        )
    }

    showRenameFile?.let { file ->
        NameDialog(
            title       = "Rename",
            placeholder = file.nameWithoutExtension,
            confirmText = "Rename",
            onConfirm   = { viewModel.renameScript(file, it) },
            onDismiss   = { viewModel.dismissRenameDialog() }
        )
    }

    // ── Screen toggle: list ↔ editor ──────────────────────────────────────────
    AnimatedContent(
        targetState = openFile,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "scripts_transition"
    ) { file ->
        if (file == null) {
            ScriptListView(viewModel)
        } else {
            ScriptEditorView(viewModel, file)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Script list
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScriptListView(viewModel: ScriptsViewModel) {
    val scripts       by viewModel.scripts.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val logs          by viewModel.logs.collectAsState()
    val targetPackage by viewModel.targetPackage.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val isInjected    by viewModel.isInjected.collectAsState()

    var exportMsg     by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Scripts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { viewModel.showNewDialog() }) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New")
            }
        }

        // Target app selector
        TargetAppCard(
            apps = installedApps,
            selected = targetPackage,
            onSelect = { viewModel.setTargetPackage(it) }
        )

        // Script list
        if (scripts.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No scripts yet", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tap + New to create your first Frida script",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    scripts.forEachIndexed { index, file ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ScriptListItem(
                            file      = file,
                            isTarget  = targetPackage.isNotEmpty(),
                            isLoading = isLoading,
                            onOpen    = { viewModel.openScript(file) },
                            onInject  = { viewModel.injectScript(file) },
                            onStop    = { viewModel.stopInjection() },
                            onRename  = { viewModel.showRenameDialog(file) },
                            onDelete  = { viewModel.deleteScript(file) },
                            isInjected = isInjected
                        )
                    }
                }
            }
        }

        // Console
        ConsolePanel(
            logs     = logs,
            onClear  = { viewModel.clearLogs() },
            onExport = {
                viewModel.exportLogsToFile { msg ->
                    exportMsg = msg
                }
            }
        )
    }

    exportMsg?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            exportMsg = null
        }
        Snackbar(modifier = Modifier.padding(16.dp)) { Text(msg) }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ScriptListItem(
    file: File,
    isTarget: Boolean,
    isLoading: Boolean,
    isInjected: Boolean,
    onOpen: () -> Unit,
    onInject: () -> Unit,
    onStop: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                formatFileSize(file.length()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Inject / Stop button
        if (isInjected) {
            IconButton(onClick = onStop, enabled = !isLoading) {
                Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFF44336))
            }
        } else {
            IconButton(onClick = onInject, enabled = !isLoading && isTarget) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Inject", tint = Color(0xFF4CAF50))
            }
        }
        // Context menu
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    onClick = { showMenu = false; onOpen() }
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null) },
                    onClick = { showMenu = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = Color(0xFFF44336)) },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFF44336)) },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Script editor
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ScriptEditorView(viewModel: ScriptsViewModel, file: File) {
    val editorContent by viewModel.editorContent.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val isDirty       by viewModel.isDirty.collectAsState()
    val isInjected    by viewModel.isInjected.collectAsState()
    val targetPackage by viewModel.targetPackage.collectAsState()
    val logs          by viewModel.logs.collectAsState()

    var exportMsg     by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Editor toolbar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.closeEditor() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = file.name + if (isDirty) " •" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            // Save
            IconButton(onClick = { viewModel.saveCurrentScript() }, enabled = !isLoading) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save",
                    tint = if (isDirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Inject / Stop
            if (isInjected) {
                IconButton(onClick = { viewModel.stopInjection() }, enabled = !isLoading) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFF44336))
                }
            } else {
                IconButton(
                    onClick = { viewModel.injectScript(file) },
                    enabled = !isLoading && targetPackage.isNotEmpty()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Inject", tint = Color(0xFF4CAF50))
                }
            }
        }

        if (targetPackage.isEmpty()) {
            Text(
                "← Go back and select a target app to enable injection",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF9800),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Code editor ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A1A1A))
        ) {
            BasicTextField(
                value = editorContent,
                onValueChange = { viewModel.onContentChanged(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                textStyle = TextStyle(
                    color = Color(0xFFD4D4D4),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            )
        }

        // ── Mini console (bottom) ─────────────────────────────────────────────
        if (logs.isNotEmpty()) {
            MiniConsole(
                logs = logs,
                onClear = { viewModel.clearLogs() },
                onExport = {
                    viewModel.exportLogsToFile { msg -> exportMsg = msg }
                }
            )
        }
    }

    exportMsg?.let { msg ->
        LaunchedEffect(msg) {
            kotlinx.coroutines.delay(3000)
            exportMsg = null
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Snackbar(modifier = Modifier.padding(16.dp)) { Text(msg) }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
            contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MiniConsole(logs: List<String>, onClear: () -> Unit, onExport: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Console", style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
            Row {
                TextButton(onClick = onExport, contentPadding = PaddingValues(4.dp)) {
                    Text("Export", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onClear, contentPadding = PaddingValues(4.dp)) {
                    Text("Clear", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(140.dp).padding(horizontal = 8.dp)) {
            LazyColumn(state = listState) {
                items(logs) { line ->
                    Text(line, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared composables
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetAppCard(
    apps: List<ScriptsViewModel.AppInfo>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = apps.firstOrNull { it.packageName == selected }?.name ?: selected

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Target App", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                OutlinedTextField(
                    value = if (selected.isEmpty()) "" else if (displayName != selected) "$displayName\n$selected" else selected,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Select app to hook") },
                    placeholder = { Text("No app selected") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                        .fillMaxWidth(),
                    maxLines = 2,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    if (apps.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Loading apps…", style = MaterialTheme.typography.bodySmall) },
                            onClick = {}
                        )
                    } else {
                        apps.forEach { app ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(app.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { onSelect(app.packageName); expanded = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsolePanel(logs: List<String>, onClear: () -> Unit, onExport: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Console", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row {
                    TextButton(onClick = onExport) {
                        Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Export")
                    }
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF1A1A1A), MaterialTheme.shapes.small)
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text("No output yet — inject a script to see logs here",
                        color = Color(0xFF666666), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState) {
                        items(logs) { line ->
                            Text(line, color = Color(0xFF00FF00), fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Script name") },
                placeholder = { Text(placeholder) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
