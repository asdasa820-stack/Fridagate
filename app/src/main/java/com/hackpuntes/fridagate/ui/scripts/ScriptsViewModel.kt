package com.hackpuntes.fridagate.ui.scripts

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hackpuntes.fridagate.utils.UserScriptUtils
import com.hackpuntes.fridagate.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ScriptsViewModel(private val context: Context) : ViewModel() {

    data class AppInfo(val name: String, val packageName: String)

    // ─────────────────────────────────────────────────────────────────────────
    // UI state
    // ─────────────────────────────────────────────────────────────────────────

    /** All .js files found in the scripts folder */
    private val _scripts = MutableStateFlow<List<File>>(emptyList())
    val scripts: StateFlow<List<File>> = _scripts.asStateFlow()

    /** Currently open file (null = list view) */
    private val _openFile = MutableStateFlow<File?>(null)
    val openFile: StateFlow<File?> = _openFile.asStateFlow()

    /** Content shown in the editor */
    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    /** True while saving / injecting */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** Console / log lines */
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    /** Currently injected state */
    private val _isInjected = MutableStateFlow(false)
    val isInjected: StateFlow<Boolean> = _isInjected.asStateFlow()

    /** Spawn = kill+relaunch (-f), Attach = hook running process (-n) */
    private val _injectMode = MutableStateFlow(InjectMode.SPAWN)
    val injectMode: StateFlow<InjectMode> = _injectMode.asStateFlow()

    enum class InjectMode { SPAWN, ATTACH }

    /** Target package for injection */
    private val _targetPackage = MutableStateFlow("")
    val targetPackage: StateFlow<String> = _targetPackage.asStateFlow()

    /** Installed (non-system) apps for the dropdown */
    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    /** Running processes from ps (for attach mode) */
    private val _runningProcesses = MutableStateFlow<List<UserScriptUtils.RunningProcess>>(emptyList())
    val runningProcesses: StateFlow<List<UserScriptUtils.RunningProcess>> = _runningProcesses.asStateFlow()

    private val _isLoadingProcesses = MutableStateFlow(false)
    val isLoadingProcesses: StateFlow<Boolean> = _isLoadingProcesses.asStateFlow()

    /** .js files found in /sdcard/Download/ */
    private val _downloadableScripts = MutableStateFlow<List<String>>(emptyList())
    val downloadableScripts: StateFlow<List<String>> = _downloadableScripts.asStateFlow()

    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    /** Show rename/new-name dialog */
    private val _showRenameDialog = MutableStateFlow<File?>(null)
    val showRenameDialog: StateFlow<File?> = _showRenameDialog.asStateFlow()

    /** Show new-script dialog */
    private val _showNewDialog = MutableStateFlow(false)
    val showNewDialog: StateFlow<Boolean> = _showNewDialog.asStateFlow()

    /** Unsaved changes flag */
    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Live-console polling
    // ─────────────────────────────────────────────────────────────────────────

    private var pollJob: Job? = null
    private var lastLogLines = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────────────

    init {
        loadScripts()
        loadInstalledApps()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Script list
    // ─────────────────────────────────────────────────────────────────────────

    fun loadScripts() {
        viewModelScope.launch {
            _scripts.value = UserScriptUtils.listScripts(context)
        }
    }

    fun openScript(file: File) {
        viewModelScope.launch {
            val content = UserScriptUtils.readScript(file)
            _openFile.value = file
            _editorContent.value = content
            _isDirty.value = false
        }
    }

    fun closeEditor() {
        _openFile.value = null
        _editorContent.value = ""
        _isDirty.value = false
    }

    fun onContentChanged(text: String) {
        _editorContent.value = text
        _isDirty.value = true
    }

    fun saveCurrentScript() {
        val file = _openFile.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            val ok = UserScriptUtils.saveScript(file, _editorContent.value)
            if (ok) {
                _isDirty.value = false
                addLog("Saved ${file.name} ✓")
            } else {
                addLog("ERROR: could not save ${file.name}")
            }
            _isLoading.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // New / rename / delete
    // ─────────────────────────────────────────────────────────────────────────

    fun showNewDialog() { _showNewDialog.value = true }
    fun dismissNewDialog() { _showNewDialog.value = false }

    fun createScript(name: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val file = UserScriptUtils.createScript(context, name)
            if (file != null) {
                loadScripts()
                openScript(file)
                addLog("Created ${file.name}")
            } else {
                addLog("ERROR: A script with that name already exists")
            }
            _showNewDialog.value = false
            _isLoading.value = false
        }
    }

    fun showRenameDialog(file: File) { _showRenameDialog.value = file }
    fun dismissRenameDialog() { _showRenameDialog.value = null }

    fun renameScript(file: File, newName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val renamed = UserScriptUtils.renameScript(file, newName)
            if (renamed != null) {
                loadScripts()
                if (_openFile.value == file) openScript(renamed)
                addLog("Renamed → ${renamed.name}")
            } else {
                addLog("ERROR: Name already exists or rename failed")
            }
            _showRenameDialog.value = null
            _isLoading.value = false
        }
    }

    fun deleteScript(file: File) {
        viewModelScope.launch {
            _isLoading.value = true
            if (_openFile.value == file) closeEditor()
            val ok = UserScriptUtils.deleteScript(file)
            if (ok) {
                loadScripts()
                addLog("Deleted ${file.name}")
            } else {
                addLog("ERROR: Could not delete ${file.name}")
            }
            _isLoading.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Injection
    // ─────────────────────────────────────────────────────────────────────────

    fun setTargetPackage(pkg: String) { _targetPackage.value = pkg }
    fun setInjectMode(mode: InjectMode) { _injectMode.value = mode }

    fun refreshProcesses() {
        viewModelScope.launch {
            _isLoadingProcesses.value = true
            _runningProcesses.value = UserScriptUtils.listRunningProcesses()
            _isLoadingProcesses.value = false
            addLog("Refreshed process list (${_runningProcesses.value.size} found)")
        }
    }

    /** Save (if dirty) then inject/attach the selected script */
    fun injectScript(file: File) {
        val pkg = _targetPackage.value.trim()
        if (pkg.isEmpty()) { addLog("ERROR: Select a target app first"); return }

        viewModelScope.launch {
            _isLoading.value = true

            // Auto-save if there are unsaved changes
            if (_isDirty.value) {
                UserScriptUtils.saveScript(file, _editorContent.value)
                _isDirty.value = false
                addLog("Auto-saved ${file.name}")
            }

            val mode = _injectMode.value
            addLog("${if (mode == InjectMode.SPAWN) "Spawning" else "Attaching"} ${file.name} → $pkg ...")

            val ok = if (mode == InjectMode.SPAWN) {
                UserScriptUtils.injectScript(file, pkg) { line -> addLog(line) }
            } else {
                UserScriptUtils.attachScript(file, pkg) { line -> addLog(line) }
            }
            _isInjected.value = ok

            if (ok) startPolling()
            _isLoading.value = false
        }
    }

    fun stopInjection() {
        val pkg = _targetPackage.value.trim()
        viewModelScope.launch {
            stopPolling()
            UserScriptUtils.stopInjection(pkg) { line -> addLog(line) }
            _isInjected.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import from Downloads
    // ─────────────────────────────────────────────────────────────────────────

    fun showImportDialog() {
        viewModelScope.launch {
            _downloadableScripts.value = UserScriptUtils.listDownloadableScripts()
            _showImportDialog.value = true
        }
    }

    fun dismissImportDialog() { _showImportDialog.value = false }

    fun importScript(downloadPath: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val file = UserScriptUtils.importFromDownloads(context, downloadPath)
            if (file != null) {
                loadScripts()
                addLog("Imported ${file.name} ✓")
                openScript(file)
            } else {
                addLog("ERROR: File already exists or import failed")
            }
            _showImportDialog.value = false
            _isLoading.value = false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Live-console polling
    // ─────────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        stopPolling()
        lastLogLines = 0
        pollJob = viewModelScope.launch {
            while (true) {
                delay(1500)
                val allLines = UserScriptUtils.pollLog()
                if (allLines.size > lastLogLines) {
                    allLines.drop(lastLogLines).forEach { addLog("  $it") }
                    lastLogLines = allLines.size
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun clearLogs() { _logs.value = emptyList(); addLog("Console cleared") }

    /** Returns all log lines as a single String for export */
    fun exportLogsAsText(): String {
        val header = "=== Fridagate 2.0 – Script Console ===\n" +
            "Package : ${_targetPackage.value}\n" +
            "Script  : ${_openFile.value?.name ?: "—"}\n" +
            "Exported: ${java.util.Date()}\n" +
            "=======================================\n\n"
        return header + _logs.value.joinToString("\n")
    }

    /** Saves log to /sdcard/Download/fridagate_log_<timestamp>.txt via root */
    fun exportLogsToFile(onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val ts = System.currentTimeMillis()
            val fileName = "fridagate_log_$ts.txt"
            val tmpFile = File(context.filesDir, fileName)
            tmpFile.writeText(exportLogsAsText())
            val dest = "/sdcard/Download/$fileName"
            com.hackpuntes.fridagate.utils.RootUtils.executeSuCommand("cp ${tmpFile.absolutePath} $dest")
            tmpFile.delete()
            val check = com.hackpuntes.fridagate.utils.RootUtils.executeSuCommand("ls $dest")
            val msg = if (check.contains(fileName)) "Log exported → $dest" else "ERROR: export failed"
            onDone(msg)
        }
    }

    private fun addLog(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _logs.value = _logs.value + "[$time] $message"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Apps list
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledPackages(0)
                .filter { pkg ->
                    val flags = pkg.applicationInfo?.flags ?: 0
                    (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                }
                .map { pkg ->
                    AppInfo(
                        name = pkg.applicationInfo?.loadLabel(pm)?.toString() ?: pkg.packageName,
                        packageName = pkg.packageName
                    )
                }
                .sortedBy { it.name.lowercase() }
            _installedApps.value = apps
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }
}
