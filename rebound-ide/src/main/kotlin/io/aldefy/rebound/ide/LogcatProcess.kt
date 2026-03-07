package io.aldefy.rebound.ide

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class LogcatProcess(
    private val onEntry: (ComposableEntry) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {

    private val log = Logger.getInstance(LogcatProcess::class.java)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true

        val thread = Thread({
            try {
                val adb = resolveAdb()
                if (adb == null) {
                    val msg = "Cannot find adb. Set ANDROID_HOME or ANDROID_SDK_ROOT environment variable."
                    log.warn(msg)
                    onError?.invoke(msg)
                    running = false
                    return@Thread
                }
                log.info("Using adb at: $adb")

                val pb = ProcessBuilder(adb, "logcat", "-s", "Rebound:*", "-v", "raw")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc

                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    var line: String?
                    while (running) {
                        line = reader.readLine()
                        if (line == null) break
                        val entry = LogcatParser.parse(line)
                        if (entry != null) {
                            onEntry(entry)
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = "Logcat process error: ${e.message}"
                log.warn(msg, e)
                onError?.invoke(msg)
            } finally {
                running = false
            }
        }, "Rebound-Logcat")
        thread.isDaemon = true
        thread.start()
    }

    fun stop() {
        running = false
        process?.destroyForcibly()
        process = null
    }

    val isRunning: Boolean get() = running

    companion object {
        fun resolveAdb(): String? {
            // 1. Try ANDROID_HOME / ANDROID_SDK_ROOT
            val sdkDir = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
            if (sdkDir != null) {
                val adb = File(sdkDir, "platform-tools/adb")
                if (adb.canExecute()) return adb.absolutePath
            }

            // 2. Common macOS SDK location
            val userHome = System.getProperty("user.home")
            for (path in listOf(
                "$userHome/Library/Android/sdk/platform-tools/adb",
                "$userHome/Android/Sdk/platform-tools/adb",
                "/usr/local/bin/adb",
                "/opt/homebrew/bin/adb"
            )) {
                val f = File(path)
                if (f.canExecute()) return f.absolutePath
            }

            // 3. Fall back to bare "adb" on PATH
            return try {
                val p = ProcessBuilder("which", "adb").start()
                val result = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                if (p.exitValue() == 0 && result.isNotEmpty()) result else null
            } catch (_: Exception) {
                null
            }
        }
    }
}
