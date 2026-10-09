package com.hackpuntes.fridagate.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UserScriptUtils – manages user-created .js script files stored in the app's
 * private files directory (context.filesDir/scripts/).
 *
 * Scripts live on-device at:
 *   /data/data/com.hackpuntes.fridagate/files/scripts/<name>.js
 *
 * They are injected by copying to /data/local/tmp/ and running via frida-inject.
 */
object UserScriptUtils {

    private const val SCRIPTS_DIR_NAME = "scripts"
    private const val TMP_DIR = "/data/local/tmp"

    // ─────────────────────────────────────────────────────────────────────────
    // Directory helpers
    // ─────────────────────────────────────────────────────────────────────────

    fun scriptsDir(context: Context): File {
        val dir = File(context.filesDir, SCRIPTS_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun listScripts(context: Context): List<File> = withContext(Dispatchers.IO) {
        scriptsDir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    suspend fun readScript(file: File): String = withContext(Dispatchers.IO) {
        file.readText()
    }

    suspend fun saveScript(file: File, content: String): Boolean = withContext(Dispatchers.IO) {
        try {
            file.writeText(content)
            true
        } catch (e: Exception) { false }
    }

    suspend fun createScript(context: Context, name: String): File? = withContext(Dispatchers.IO) {
        val safeName = name.trim().let {
            if (it.endsWith(".js")) it else "$it.js"
        }
        val file = File(scriptsDir(context), safeName)
        if (file.exists()) return@withContext null
        val template = """// Fridagate 2.0 – $safeName
// Target: set in the Scripts tab → select package
// Docs: https://frida.re/docs/javascript-api/

Java.perform(function () {
    // Example: hook a method
    // var SomeClass = Java.use("com.example.SomeClass");
    // SomeClass.someMethod.implementation = function (arg) {
    //     console.log("[*] someMethod called with: " + arg);
    //     return this.someMethod(arg);
    // };
    console.log("[*] Script loaded");
});
"""
        file.writeText(template)
        file
    }

    suspend fun deleteScript(file: File): Boolean = withContext(Dispatchers.IO) {
        try { file.delete() } catch (e: Exception) { false }
    }

    suspend fun renameScript(file: File, newName: String): File? = withContext(Dispatchers.IO) {
        val safeName = newName.trim().let { if (it.endsWith(".js")) it else "$it.js" }
        val dest = File(file.parent!!, safeName)
        if (dest.exists()) return@withContext null
        if (file.renameTo(dest)) dest else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deployment (copy to /data/local/tmp via root)
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun deployToTmp(file: File): String? = withContext(Dispatchers.IO) {
        val dest = "$TMP_DIR/${file.name}"
        try {
            RootUtils.executeSuCommand("cp ${file.absolutePath} $dest")
            RootUtils.executeSuCommand("chmod 644 $dest")
            val check = RootUtils.executeSuCommand("ls $dest")
            if (check.contains(file.name) && !check.contains("No such file")) dest else null
        } catch (e: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inject via frida-inject
    // ─────────────────────────────────────────────────────────────────────────

    private const val INJECT_LOG = "/data/local/tmp/fridagate_user_inject.log"
    private var injectedPid: String? = null

    suspend fun injectScript(
        file: File,
        packageName: String,
        onLog: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val injectBin = FridaInjectUtils.INJECT_BINARY_PATH
        val check = RootUtils.executeSuCommand("ls $injectBin")
        if (!check.contains("frida-inject") || check.contains("No such file")) {
            onLog("ERROR: frida-inject not installed — go to Extras tab and download it")
            return@withContext false
        }

        val tmpPath = deployToTmp(file)
        if (tmpPath == null) {
            onLog("ERROR: Could not copy script to /data/local/tmp/")
            return@withContext false
        }
        onLog("Script deployed → $tmpPath")

        // Kill old instance
        RootUtils.executeSuCommand("am force-stop $packageName")
        Thread.sleep(500)
        onLog("Stopped $packageName")

        // Clear old log
        RootUtils.executeSuCommand("rm -f $INJECT_LOG")

        // Run frida-inject (background, don't wait)
        val cmd = "$injectBin -f $packageName -s $tmpPath -e > $INJECT_LOG 2>&1 &"
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        } catch (e: Exception) {
            onLog("ERROR launching frida-inject: ${e.message}")
            return@withContext false
        }

        // Wait for attach
        Thread.sleep(3500)

        val log = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
        if (log.isNotEmpty()) {
            log.lines().filter { it.isNotBlank() }.forEach { onLog("  $it") }
        }

        // Check PID
        val pid = findPid(packageName)
        return@withContext if (pid != null) {
            injectedPid = pid
            onLog("✓ $packageName running (PID $pid)")
            true
        } else {
            onLog("Process not found — is frida-server running?")
            false
        }
    }

    suspend fun stopInjection(packageName: String, onLog: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                RootUtils.executeSuCommand("am force-stop $packageName")
                onLog("Stopped $packageName")
            } catch (e: Exception) {
                onLog("ERROR stopping: ${e.message}")
            }
            injectedPid = null
        }

    /** Poll frida-inject log for new lines (live console) */
    suspend fun pollLog(): List<String> = withContext(Dispatchers.IO) {
        try {
            val out = RootUtils.executeSuCommand("cat $INJECT_LOG").trim()
            if (out.isEmpty()) emptyList()
            else out.lines().filter { it.isNotBlank() }
        } catch (e: Exception) { emptyList() }
    }

    private fun findPid(packageName: String): String? {
        var pid = RootUtils.executeSuCommand("pidof $packageName").trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() || it == ' ' }) return pid.trim()
        val ps = RootUtils.executeSuCommand("ps -A | grep $packageName")
        if (ps.isNotEmpty() && !ps.contains("grep")) {
            val parts = ps.trim().split("\\s+".toRegex())
            if (parts.size > 1) return parts[1]
        }
        return null
    }
}
