package cn.ppps.forwarder.utils

import android.os.Build
import cn.ppps.forwarder.App
import frpclib.Frpclib
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object FrpcCompat {

    private const val TAG = "FrpcCompat"
    private const val CUSTOM_BINARY_NAME = "frpc"
    private const val CUSTOM_BINARY_DIR = "libs"
    private const val CUSTOM_ASSET_DIR = "frpc"
    private const val CONFIG_DIR = "frpc"
    private val processMap = ConcurrentHashMap<String, Process>()
    private val lastErrorMap = ConcurrentHashMap<String, String>()
    private val installLock = Any()
    @Volatile
    private var customBackendUsable: Boolean? = null

    private fun getCurrentAbi(): String {
        val abi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.firstOrNull()
        } else {
            Build.CPU_ABI
        } ?: "armeabi-v7a"

        return when {
            abi.startsWith("arm64") -> "arm64-v8a"
            abi.startsWith("armeabi") || abi.startsWith("arm") -> "armeabi-v7a"
            abi == "x86_64" -> "x86_64"
            abi == "x86" -> "x86"
            else -> "armeabi-v7a"
        }
    }

    private fun getCustomBinaryFile(): File {
        val dir = File(App.context.filesDir, CUSTOM_BINARY_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, CUSTOM_BINARY_NAME)
    }

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    private fun cleanupProcessMap() {
        val iterator = processMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!isProcessAlive(entry.value)) {
                iterator.remove()
            }
        }
    }

    fun ensureCustomBinaryInstalled(): Boolean {
        synchronized(installLock) {
            val customBinary = getCustomBinaryFile()
            if (customBinary.exists()) {
                customBinary.setExecutable(true, false)
                return customBinary.canExecute()
            }

            val abi = getCurrentAbi()
            val assetPath = "$CUSTOM_ASSET_DIR/$abi/$CUSTOM_BINARY_NAME"
            return try {
                App.context.assets.open(assetPath).use { input ->
                    FileOutputStream(customBinary).use { output ->
                        input.copyTo(output)
                    }
                }
                customBinary.setReadable(true, false)
                customBinary.setWritable(true, true)
                customBinary.setExecutable(true, false)
                customBackendUsable = null
                customBinary.canExecute()
            } catch (e: Exception) {
                Log.d(TAG, "custom frpc asset not found for abi=$abi, fallback to jni backend: ${e.message}")
                false
            }
        }
    }

    private fun markCustomBackendUnavailable(reason: String) {
        customBackendUsable = false
        Log.e(TAG, "disable custom backend, reason=$reason")
    }

    private fun setLastError(uid: String, error: String) {
        if (uid.isEmpty()) return
        if (error.isBlank()) return
        lastErrorMap[uid] = error
    }

    private fun clearLastError(uid: String) {
        if (uid.isEmpty()) return
        lastErrorMap.remove(uid)
    }

    fun getLastError(uid: String): String {
        if (uid.isEmpty()) return ""
        return lastErrorMap[uid] ?: ""
    }

    private fun probeCustomBackend(customBinary: File): Boolean {
        return try {
            val process = ProcessBuilder(customBinary.absolutePath, "--version")
                .directory(App.context.filesDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                Log.e(TAG, "custom backend probe timeout")
                false
            } else {
                val exitCode = process.exitValue()
                val ok = exitCode == 0 || output.contains("frpc", ignoreCase = true)
                if (!ok) {
                    Log.e(TAG, "custom backend probe failed: code=$exitCode, output=$output")
                }
                ok
            }
        } catch (e: Exception) {
            Log.e(TAG, "custom backend probe exception: ${e.message}")
            false
        }
    }

    private fun hasCustomBackend(): Boolean {
        val binary = getCustomBinaryFile()
        val installed = (binary.exists() && binary.canExecute()) || ensureCustomBinaryInstalled()
        if (!installed) return false

        customBackendUsable?.let { return it }
        val usable = probeCustomBackend(binary)
        customBackendUsable = usable
        return usable
    }

    private fun isBackendBinaryError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("exec format error")
            || lower.contains("permission denied")
            || lower.contains("bad cpu type")
            || lower.contains("no such file or directory")
    }

    private fun getVersionByJni(): String {
        return try {
            Frpclib.getVersion()
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc getVersion error: ${e.message}")
            ""
        }
    }

    private fun getUidsByJni(): String {
        return try {
            Frpclib.getUids()
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc getUids error: ${e.message}")
            ""
        }
    }

    private fun isRunningByJni(uid: String): Boolean {
        return try {
            Frpclib.isRunning(uid)
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc isRunning error: ${e.message}")
            false
        }
    }

    private fun closeByJni(uid: String): Boolean {
        return try {
            Frpclib.close(uid)
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc close error: ${e.message}")
            false
        }
    }

    private fun runContentByJni(uid: String, config: String): String {
        return try {
            Frpclib.runContent(uid, config)
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc runContent error: ${e.message}")
            e.message ?: "jni frpc unavailable"
        }
    }

    private fun runFileByJni(uid: String, configPath: String): String {
        return try {
            Frpclib.runFile(uid, configPath)
        } catch (e: Throwable) {
            Log.e(TAG, "jni frpc runFile error: ${e.message}")
            e.message ?: "jni frpc unavailable"
        }
    }

    fun isReady(): Boolean {
        return try {
            val version = getVersion()
            version == FRPC_LIB_VERSION || version == FRPC_CUSTOM_VERSION
        } catch (e: Exception) {
            Log.e(TAG, "isReady error: ${e.message}")
            false
        }
    }

    fun getVersion(): String {
        return if (hasCustomBackend()) {
            FRPC_CUSTOM_VERSION
        } else {
            getVersionByJni()
        }
    }

    fun getUids(): String {
        return if (hasCustomBackend()) {
            cleanupProcessMap()
            processMap.keys.sorted().joinToString(",")
        } else {
            getUidsByJni()
        }
    }

    fun isRunning(uid: String): Boolean {
        if (uid.isEmpty()) return false

        return if (hasCustomBackend()) {
            cleanupProcessMap()
            val process = processMap[uid] ?: return false
            isProcessAlive(process)
        } else {
            isRunningByJni(uid)
        }
    }

    fun close(uid: String): Boolean {
        if (uid.isEmpty()) return false

        return if (hasCustomBackend()) {
            clearLastError(uid)
            val process = processMap.remove(uid) ?: return false
            return try {
                process.destroy()
                true
            } catch (e: Exception) {
                Log.e(TAG, "close process error: ${e.message}")
                false
            }
        } else {
            closeByJni(uid)
        }
    }

    fun runContent(uid: String, config: String): String {
        if (!hasCustomBackend()) {
            val error = runContentByJni(uid, config)
            if (error.isNotEmpty()) setLastError(uid, error) else clearLastError(uid)
            return error
        }
        if (uid.isEmpty()) return "frpc uid is empty"
        clearLastError(uid)

        val configDir = File(App.context.filesDir, CONFIG_DIR)
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val configFile = File(configDir, "$uid.toml")
        return try {
            configFile.writeText(config)
            runFile(uid, configFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "runContent error: ${e.message}")
            val error = e.message ?: "runContent error"
            setLastError(uid, error)
            error
        }
    }

    fun runFile(uid: String, configPath: String): String {
        if (!hasCustomBackend()) {
            val error = runFileByJni(uid, configPath)
            if (error.isNotEmpty()) setLastError(uid, error) else clearLastError(uid)
            return error
        }
        if (uid.isEmpty()) return "frpc uid is empty"
        if (isRunning(uid)) return ""
        clearLastError(uid)

        val configFile = File(configPath)
        if (!configFile.exists()) {
            val error = "config file not found: $configPath"
            setLastError(uid, error)
            return error
        }

        val customBinary = getCustomBinaryFile()
        if (!customBinary.exists() || !customBinary.canExecute()) {
            val error = "frpc binary not found"
            setLastError(uid, error)
            return error
        }

        return try {
            val process = ProcessBuilder(
                customBinary.absolutePath,
                "-c",
                configFile.absolutePath
            )
                .directory(App.context.filesDir)
                .redirectErrorStream(true)
                .start()

            val exitedQuickly = process.waitFor(500, TimeUnit.MILLISECONDS)
            if (exitedQuickly) {
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                val errorMessage = if (output.isEmpty()) {
                    "custom frpc exited early, code=${process.exitValue()}"
                } else {
                    output
                }
                if (isBackendBinaryError(errorMessage)) {
                    markCustomBackendUnavailable(errorMessage)
                    val fallbackError = runFileByJni(uid, configPath)
                    if (fallbackError.isNotEmpty()) setLastError(uid, fallbackError)
                    return fallbackError
                }
                setLastError(uid, errorMessage)
                return errorMessage
            }

            processMap[uid] = process

            Thread {
                val logs = mutableListOf<String>()
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            logs.add(line)
                            Log.d(TAG, "[$uid] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read process logs error: ${e.message}")
                } finally {
                    val exitCode = try {
                        process.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        Int.MIN_VALUE
                    }
                    if (exitCode != Int.MIN_VALUE && exitCode != 0 && getLastError(uid).isEmpty()) {
                        val tailLog = logs.takeLast(8).joinToString("\n").trim()
                        val error = if (tailLog.isEmpty()) {
                            "frpc exited with code $exitCode"
                        } else {
                            "frpc exited with code $exitCode\n$tailLog"
                        }
                        setLastError(uid, error)
                    }
                    processMap.remove(uid)
                }
            }.start()

            ""
        } catch (e: Exception) {
            Log.e(TAG, "runFile error: ${e.message}")
            val message = e.message ?: "runFile error"
            return if (isBackendBinaryError(message)) {
                markCustomBackendUnavailable(message)
                val fallbackError = runFileByJni(uid, configPath)
                if (fallbackError.isNotEmpty()) setLastError(uid, fallbackError)
                fallbackError
            } else {
                setLastError(uid, message)
                message
            }
        }
    }
}
