package cn.ppps.forwarder.utils

import android.os.Build
import cn.ppps.forwarder.App
import frpclib.Frpclib
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object FrpcCompat {

    private const val TAG = "FrpcCompat"
    private const val CUSTOM_BINARY_NAME = "frpc"
    private const val CUSTOM_BINARY_DIR = "libs"
    private const val CUSTOM_ASSET_DIR = "frpc"
    private const val CONFIG_DIR = "frpc"
    private val processMap = ConcurrentHashMap<String, Process>()
    private val installLock = Any()

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
                customBinary.canExecute()
            } catch (e: Exception) {
                Log.d(TAG, "custom frpc asset not found for abi=$abi, fallback to jni backend: ${e.message}")
                false
            }
        }
    }

    private fun hasCustomBackend(): Boolean {
        val binary = getCustomBinaryFile()
        return (binary.exists() && binary.canExecute()) || ensureCustomBinaryInstalled()
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
            try {
                Frpclib.getVersion()
            } catch (e: Throwable) {
                Log.e(TAG, "jni frpc getVersion error: ${e.message}")
                ""
            }
        }
    }

    fun getUids(): String {
        return if (hasCustomBackend()) {
            cleanupProcessMap()
            processMap.keys.sorted().joinToString(",")
        } else {
            Frpclib.getUids()
        }
    }

    fun isRunning(uid: String): Boolean {
        if (uid.isEmpty()) return false

        return if (hasCustomBackend()) {
            cleanupProcessMap()
            val process = processMap[uid] ?: return false
            isProcessAlive(process)
        } else {
            Frpclib.isRunning(uid)
        }
    }

    fun close(uid: String): Boolean {
        if (uid.isEmpty()) return false

        return if (hasCustomBackend()) {
            val process = processMap.remove(uid) ?: return false
            return try {
                process.destroy()
                true
            } catch (e: Exception) {
                Log.e(TAG, "close process error: ${e.message}")
                false
            }
        } else {
            Frpclib.close(uid)
        }
    }

    fun runContent(uid: String, config: String): String {
        if (!hasCustomBackend()) {
            return Frpclib.runContent(uid, config)
        }
        if (uid.isEmpty()) return "frpc uid is empty"

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
            e.message ?: "runContent error"
        }
    }

    fun runFile(uid: String, configPath: String): String {
        if (!hasCustomBackend()) {
            return Frpclib.runFile(uid, configPath)
        }
        if (uid.isEmpty()) return "frpc uid is empty"
        if (isRunning(uid)) return ""

        val configFile = File(configPath)
        if (!configFile.exists()) {
            return "config file not found: $configPath"
        }

        val customBinary = getCustomBinaryFile()
        if (!customBinary.exists() || !customBinary.canExecute()) {
            return "frpc binary not found"
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

            processMap[uid] = process

            Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Log.d(TAG, "[$uid] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read process logs error: ${e.message}")
                } finally {
                    processMap.remove(uid)
                }
            }.start()

            ""
        } catch (e: Exception) {
            Log.e(TAG, "runFile error: ${e.message}")
            e.message ?: "runFile error"
        }
    }
}
