package cn.ppps.forwarder.utils

import android.os.Build
import cn.ppps.forwarder.App
import cn.ppps.forwarder.BuildConfig
import frpclib.Frpclib
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object FrpcCompat {

    private const val TAG = "FrpcCompat"
    private const val CUSTOM_BINARY_NAME = "frpc"
    private const val CUSTOM_BINARY_PACKED_NAME = "frpc.bin"
    private const val CUSTOM_BINARY_COMPRESSED_NAME = "frpc.gz"
    private const val CUSTOM_NATIVE_BINARY_NAME = "libfrpc.so"
    private const val CUSTOM_BINARY_DIR = "libs"
    private const val CUSTOM_BINARY_BUILD_INFO_NAME = "frpc.buildinfo"
    private const val CUSTOM_ASSET_DIR = "frpc"
    private const val CONFIG_DIR = "frpc"
    private const val STARTUP_READY_TIMEOUT_MS = 8_000L
    private const val STARTUP_POLL_WAIT_MS = 300L
    private const val STARTUP_TAIL_LINES = 12
    private val processMap = ConcurrentHashMap<String, Process>()
    private val lastErrorMap = ConcurrentHashMap<String, String>()
    private val installLock = Any()
    @Volatile
    private var customBackendUsable: Boolean? = null

    private fun customOnlyMode(): Boolean {
        return BuildConfig.WITH_FRPC_PACKAGE
    }

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

    private fun getWritableCustomBinaryFiles(): List<File> {
        val files = linkedSetOf<File>()
        files.add(File(File(App.context.filesDir, CUSTOM_BINARY_DIR), CUSTOM_BINARY_NAME))
        files.add(File(File(App.context.codeCacheDir, CUSTOM_BINARY_DIR), CUSTOM_BINARY_NAME))
        files.add(File(File(App.context.noBackupFilesDir, CUSTOM_BINARY_DIR), CUSTOM_BINARY_NAME))
        files.add(File(File(App.context.cacheDir, CUSTOM_BINARY_DIR), CUSTOM_BINARY_NAME))
        return files.toList()
    }

    private fun getCustomBinaryFile(): File {
        return getWritableCustomBinaryFiles().first()
    }

    private fun getNativeCustomBinaryFile(): File? {
        val nativeDir = App.context.applicationInfo.nativeLibraryDir ?: return null
        if (nativeDir.isBlank()) return null
        return File(nativeDir, CUSTOM_NATIVE_BINARY_NAME)
    }

    private fun hasNativeCustomBinary(): Boolean {
        val nativeBinary = getNativeCustomBinaryFile() ?: return false
        return nativeBinary.exists() && nativeBinary.canRead() && nativeBinary.canExecute()
    }

    private fun resolveCustomBinaryFile(): File {
        val nativeBinary = getNativeCustomBinaryFile()
        if (nativeBinary != null && nativeBinary.exists() && nativeBinary.canRead() && nativeBinary.canExecute()) {
            return nativeBinary
        }
        for (candidate in getWritableCustomBinaryFiles()) {
            if (candidate.exists()) return candidate
        }
        return getCustomBinaryFile()
    }

    private fun resolveCustomBinaryCandidates(): List<File> {
        val ordered = linkedMapOf<String, File>()
        val nativeBinary = getNativeCustomBinaryFile()
        if (nativeBinary != null && nativeBinary.exists() && nativeBinary.canRead() && nativeBinary.canExecute()) {
            ordered[nativeBinary.absolutePath] = nativeBinary
        }
        for (candidate in getWritableCustomBinaryFiles()) {
            if (candidate.exists()) {
                ordered[candidate.absolutePath] = candidate
            }
        }
        if (ordered.isEmpty()) {
            val fallback = getCustomBinaryFile()
            ordered[fallback.absolutePath] = fallback
        }
        return ordered.values.toList()
    }

    private fun getCustomBinaryBuildInfoFile(): File {
        val dir = File(App.context.filesDir, CUSTOM_BINARY_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, CUSTOM_BINARY_BUILD_INFO_NAME)
    }

    private fun readAssetText(assetPath: String): String {
        return try {
            App.context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseBuildInfo(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val index = line.indexOf("=")
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                map[key] = value
            }
        }
        return map
    }

    private fun getInstalledBuildInfoText(): String {
        return try {
            val file = getCustomBinaryBuildInfoFile()
            if (file.exists()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun customUnavailableSummary(): String {
        val abi = getCurrentAbi()
        val buildInfoText = getInstalledBuildInfoText().ifEmpty {
            readAssetText("$CUSTOM_ASSET_DIR/BUILD_INFO.txt")
        }
        val buildInfo = parseBuildInfo(buildInfoText)
        val ref = buildInfo["frp_ref"].orEmpty()
        val commit = buildInfo["frp_commit"].orEmpty()
        return buildString {
            append("backend=custom unavailable")
            append(" abi=").append(abi)
            if (ref.isNotEmpty()) append(" ref=").append(ref)
            if (commit.isNotEmpty()) append(" commit=").append(commit)
        }
    }

    private fun currentBackendSummary(useCustomBackend: Boolean = hasCustomBackend()): String {
        val abi = getCurrentAbi()
        return if (useCustomBackend) {
            val buildInfoText = getInstalledBuildInfoText().ifEmpty {
                readAssetText("$CUSTOM_ASSET_DIR/BUILD_INFO.txt")
            }
            val buildInfo = parseBuildInfo(buildInfoText)
            val ref = buildInfo["frp_ref"].orEmpty()
            val commit = buildInfo["frp_commit"].orEmpty()
            buildString {
                append("backend=custom")
                append(" version=").append(FRPC_CUSTOM_VERSION)
                append(" abi=").append(abi)
                if (ref.isNotEmpty()) append(" ref=").append(ref)
                if (commit.isNotEmpty()) append(" commit=").append(commit)
            }
        } else if (customOnlyMode()) {
            customUnavailableSummary()
        } else {
            val jniVersion = getVersionByJni().ifEmpty { "unknown" }
            "backend=jni version=$jniVersion abi=$abi"
        }
    }

    private fun withBackendInfo(message: String, useCustomBackend: Boolean = hasCustomBackend()): String {
        val detail = message.trim()
        if (detail.isEmpty()) return currentBackendSummary(useCustomBackend)
        if (detail.contains("backend=", ignoreCase = true)) return detail
        return "${currentBackendSummary(useCustomBackend)} | $detail"
    }

    private fun detectConfigFileExt(config: String): String {
        val trimmed = config.trimStart()
        if (trimmed.startsWith("{")) return "json"

        val lines = config.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith(";") }
            .take(12)
            .toList()

        if (lines.any { it.equals("[common]", ignoreCase = true) }) return "ini"
        if (lines.any { it.contains(":") && !it.contains("=") }) return "yaml"
        return "toml"
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
        return synchronized(installLock) {
            if (hasNativeCustomBinary()) {
                customBackendUsable = null
                return true
            }
            val writableBinaries = getWritableCustomBinaryFiles()
            val abi = getCurrentAbi()
            val rawAssetPath = "$CUSTOM_ASSET_DIR/$abi/$CUSTOM_BINARY_NAME"
            val packedAssetPath = "$CUSTOM_ASSET_DIR/$abi/$CUSTOM_BINARY_PACKED_NAME"
            val compressedAssetPath = "$CUSTOM_ASSET_DIR/$abi/$CUSTOM_BINARY_COMPRESSED_NAME"
            val assetPath = when {
                isAssetExists(packedAssetPath) -> packedAssetPath
                isAssetExists(compressedAssetPath) -> compressedAssetPath
                isAssetExists(rawAssetPath) -> rawAssetPath
                else -> ""
            }
            val bundledBuildInfo = readAssetText("$CUSTOM_ASSET_DIR/BUILD_INFO.txt")
            val buildInfoFile = getCustomBinaryBuildInfoFile()
            val hasBundledAsset = assetPath.isNotEmpty()

            if (!hasBundledAsset) {
                writableBinaries.forEach { binary ->
                    if (binary.exists()) binary.delete()
                }
                if (buildInfoFile.exists()) buildInfoFile.delete()
                customBackendUsable = false
                Log.d(TAG, "custom frpc asset not found for abi=$abi")
                return false
            }

            val installedBuildInfo = getInstalledBuildInfoText()
            if (installedBuildInfo.isNotEmpty() && bundledBuildInfo.isNotEmpty() && installedBuildInfo == bundledBuildInfo) {
                for (binary in writableBinaries) {
                    if (!binary.exists()) continue
                    binary.setExecutable(true, false)
                    if (binary.canExecute()) {
                        return true
                    }
                }
            }

            val installErrors = mutableListOf<String>()
            for (binary in writableBinaries) {
                try {
                    val parent = binary.parentFile
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs()
                    }
                    val tempBinary = File(parent, "${CUSTOM_BINARY_NAME}.${System.nanoTime()}.tmp")
                    copyAssetBinary(assetPath, tempBinary)
                    if (binary.exists()) binary.delete()
                    if (!tempBinary.renameTo(binary)) {
                        tempBinary.copyTo(binary, overwrite = true)
                        tempBinary.delete()
                    }
                    binary.setReadable(true, false)
                    binary.setWritable(true, true)
                    binary.setExecutable(true, false)
                    if (!binary.canExecute()) {
                        installErrors.add("${binary.absolutePath}: not executable after install")
                        continue
                    }
                    if (bundledBuildInfo.isNotEmpty()) {
                        buildInfoFile.writeText(bundledBuildInfo)
                    } else if (buildInfoFile.exists()) {
                        buildInfoFile.delete()
                    }
                    customBackendUsable = null
                    return true
                } catch (e: Exception) {
                    installErrors.add("${binary.absolutePath}: ${e.message}")
                }
            }
            Log.e(TAG, "install custom frpc failed for abi=$abi: ${installErrors.joinToString(" | ")}")
            false
        }
    }

    private fun copyAssetBinary(assetPath: String, targetFile: File) {
        App.context.assets.open(assetPath).use { input ->
            BufferedInputStream(input).use { buffered ->
                buffered.mark(2)
                val header = ByteArray(2)
                val readCount = buffered.read(header)
                buffered.reset()
                val isGzip = readCount == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()
                FileOutputStream(targetFile).use { output ->
                    if (isGzip) {
                        GZIPInputStream(buffered).use { gzipInput ->
                            gzipInput.copyTo(output)
                        }
                    } else {
                        buffered.copyTo(output)
                    }
                }
            }
        }
    }

    private fun isAssetExists(assetPath: String): Boolean {
        if (assetPath.isEmpty()) return false
        return try {
            App.context.assets.open(assetPath).close()
            true
        } catch (_: Exception) {
            false
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
        val binary = resolveCustomBinaryFile()
        val hasWritableBinary = getWritableCustomBinaryFiles().any { it.exists() && it.canExecute() }
        val installed = hasNativeCustomBinary() || hasWritableBinary || (binary.exists() && binary.canExecute()) || ensureCustomBinaryInstalled()
        if (!installed) return false

        customBackendUsable?.let { return it }
        // with_frpc 包不再依赖 --version 探测，直接按可执行处理，
        // 启动时由 runFile 返回真实错误（避免探测误判导致 custom unavailable）。
        if (customOnlyMode()) {
            customBackendUsable = true
            return true
        }
        val usable = probeCustomBackend(binary)
        customBackendUsable = usable
        return usable
    }

    private fun isBackendStartError(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("exec format error")
            || lower.contains("bad cpu type")
            || lower.contains("cannot run program")
            || lower.contains("error=8")
            || lower.contains("error=13")
            || lower.contains("permission denied")
            || lower.contains("error=2")
            || lower.contains("no such file or directory")
    }

    private fun isStartupSuccessLog(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("login to server success")
            || lower.contains("start proxy success")
    }

    private fun isStartupFailureLog(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("login to server failed")
            || lower.contains("start error")
            || lower.contains("panic")
            || lower.contains("fatal")
            || lower.contains("permission denied")
            || lower.contains("connection refused")
            || lower.contains("i/o timeout")
            || lower.contains("no such host")
            || lower.contains("auth failed")
            || lower.contains("tls handshake")
            || lower.contains("token")
    }

    private fun waitForProcessExit(process: Process, timeoutMs: Long) {
        try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        }
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
            if (customOnlyMode()) {
                return hasCustomBackend()
            }
            val version = getVersion()
            version == FRPC_LIB_VERSION || version == FRPC_CUSTOM_VERSION
        } catch (e: Exception) {
            Log.e(TAG, "isReady error: ${e.message}")
            false
        }
    }

    fun getBackendSummary(): String {
        return currentBackendSummary()
    }

    fun getVersion(): String {
        return if (hasCustomBackend()) {
            FRPC_CUSTOM_VERSION
        } else if (customOnlyMode()) {
            ""
        } else {
            getVersionByJni()
        }
    }

    fun getUids(): String {
        return if (hasCustomBackend()) {
            cleanupProcessMap()
            processMap.keys.sorted().joinToString(",")
        } else if (customOnlyMode()) {
            ""
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
        } else if (customOnlyMode()) {
            false
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
        } else if (customOnlyMode()) {
            false
        } else {
            closeByJni(uid)
        }
    }

    fun runContent(uid: String, config: String): String {
        if (!hasCustomBackend()) {
            if (customOnlyMode()) {
                val detail = withBackendInfo("custom frpc backend unavailable", useCustomBackend = false)
                setLastError(uid, detail)
                return detail
            }
            val error = runContentByJni(uid, config)
            if (error.isNotEmpty()) {
                val detail = withBackendInfo(error, useCustomBackend = false)
                setLastError(uid, detail)
                return detail
            }
            clearLastError(uid)
            return ""
        }
        if (uid.isEmpty()) return "frpc uid is empty"
        clearLastError(uid)

        val configDir = File(App.context.filesDir, CONFIG_DIR)
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val configExt = detectConfigFileExt(config)
        val configFile = File(configDir, "$uid.$configExt")
        return try {
            configFile.writeText(config)
            runFile(uid, configFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "runContent error: ${e.message}")
            val error = withBackendInfo(e.message ?: "runContent error", useCustomBackend = true)
            setLastError(uid, error)
            error
        }
    }

    fun runFile(uid: String, configPath: String): String {
        if (!hasCustomBackend()) {
            if (customOnlyMode()) {
                val detail = withBackendInfo("custom frpc backend unavailable", useCustomBackend = false)
                setLastError(uid, detail)
                return detail
            }
            val error = runFileByJni(uid, configPath)
            if (error.isNotEmpty()) {
                val detail = withBackendInfo(error, useCustomBackend = false)
                setLastError(uid, detail)
                return detail
            }
            clearLastError(uid)
            return ""
        }
        if (uid.isEmpty()) return "frpc uid is empty"
        if (isRunning(uid)) return ""
        clearLastError(uid)

        val configFile = File(configPath)
        if (!configFile.exists()) {
            val error = withBackendInfo("config file not found: $configPath", useCustomBackend = true)
            setLastError(uid, error)
            return error
        }

        ensureCustomBinaryInstalled()
        val candidates = resolveCustomBinaryCandidates()
        val launchErrors = mutableListOf<String>()

        for (customBinary in candidates) {
            if (!customBinary.exists()) continue
            if (!customBinary.canExecute()) {
                customBinary.setExecutable(true, false)
            }
            if (!customBinary.canExecute()) {
                launchErrors.add("${customBinary.absolutePath}: not executable")
                continue
            }

            try {
                val process = ProcessBuilder(
                    customBinary.absolutePath,
                    "-c",
                    configFile.absolutePath
                )
                    .directory(App.context.filesDir)
                    .redirectErrorStream(true)
                    .start()

                val logLock = Object()
                val logs = mutableListOf<String>()
                Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                synchronized(logLock) {
                                    logs.add(line)
                                    logLock.notifyAll()
                                }
                                Log.d(TAG, "[$uid] $line")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "read process logs error: ${e.message}")
                    } finally {
                        synchronized(logLock) {
                            logLock.notifyAll()
                        }
                        val exitCode = try {
                            process.exitValue()
                        } catch (_: IllegalThreadStateException) {
                            Int.MIN_VALUE
                        }
                        val isActiveProcess = processMap[uid] === process
                        if (exitCode != Int.MIN_VALUE && exitCode != 0 && isActiveProcess && getLastError(uid).isEmpty()) {
                            val tailLog = synchronized(logLock) { logs.takeLast(STARTUP_TAIL_LINES).joinToString("\n").trim() }
                            val error = if (tailLog.isEmpty()) {
                                "frpc exited with code $exitCode"
                            } else {
                                "frpc exited with code $exitCode\n$tailLog"
                            }
                            setLastError(uid, withBackendInfo(error, useCustomBackend = true))
                        }
                        if (isActiveProcess) {
                            processMap.remove(uid)
                        }
                    }
                }.start()

                var checkedLogIndex = 0
                var startupReady = false
                var startupFailure: String? = null
                val deadline = System.currentTimeMillis() + STARTUP_READY_TIMEOUT_MS

                while (System.currentTimeMillis() < deadline) {
                    synchronized(logLock) {
                        while (checkedLogIndex < logs.size) {
                            val line = logs[checkedLogIndex]
                            checkedLogIndex++
                            if (isStartupFailureLog(line)) {
                                startupFailure = line
                                break
                            }
                            if (isStartupSuccessLog(line)) {
                                startupReady = true
                                break
                            }
                        }
                        if (!startupReady && startupFailure == null && isProcessAlive(process)) {
                            val remain = deadline - System.currentTimeMillis()
                            if (remain > 0) {
                                val waitMs = if (remain < STARTUP_POLL_WAIT_MS) remain else STARTUP_POLL_WAIT_MS
                                logLock.wait(waitMs)
                            }
                        }
                    }
                    if (startupReady || startupFailure != null) break
                    if (!isProcessAlive(process)) break
                }

                if (startupReady) {
                    processMap[uid] = process
                    return ""
                }

                val tailLog = synchronized(logLock) { logs.takeLast(STARTUP_TAIL_LINES).joinToString("\n").trim() }
                val wasAliveBeforeDestroy = isProcessAlive(process)
                process.destroy()
                waitForProcessExit(process, 500)

                val failReason = when {
                    startupFailure != null -> startupFailure!!
                    !wasAliveBeforeDestroy -> {
                        if (tailLog.isNotEmpty()) {
                            "frpc exited during startup: $tailLog"
                        } else {
                            "frpc exited during startup"
                        }
                    }
                    tailLog.isNotEmpty() -> {
                        "no startup success signal within ${STARTUP_READY_TIMEOUT_MS}ms, tail=$tailLog"
                    }
                    else -> {
                        "no startup success signal within ${STARTUP_READY_TIMEOUT_MS}ms"
                    }
                }
                launchErrors.add("${customBinary.absolutePath}: $failReason")
                continue
            } catch (e: Exception) {
                Log.e(TAG, "runFile error: ${e.message}, binary=${customBinary.absolutePath}")
                val message = e.message ?: "runFile error"
                if (isBackendStartError(message)) {
                    launchErrors.add("${customBinary.absolutePath}: $message")
                    continue
                }
                val detail = withBackendInfo(message, useCustomBackend = true)
                setLastError(uid, detail)
                return detail
            }
        }

        val launchSummary = if (launchErrors.isEmpty()) {
            "frpc binary not found"
        } else {
            "custom frpc start failed on all candidates: ${launchErrors.joinToString(" ; ")}"
        }
        if (customOnlyMode()) {
            customBackendUsable = null
            val detail = withBackendInfo(launchSummary, useCustomBackend = true)
            setLastError(uid, detail)
            return detail
        }

        markCustomBackendUnavailable(launchSummary)
        val fallbackError = runFileByJni(uid, configPath)
        if (fallbackError.isNotEmpty()) {
            val detail = withBackendInfo(fallbackError, useCustomBackend = false)
            setLastError(uid, detail)
            return detail
        }
        return ""
    }
}
