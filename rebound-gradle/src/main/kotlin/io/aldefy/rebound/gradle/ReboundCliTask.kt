package io.aldefy.rebound.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Gradle task that communicates with a running Rebound-instrumented app
 * via ADB port forwarding + socket.
 *
 * Usage:
 *   ./gradlew reboundSnapshot
 *   ./gradlew reboundSummary
 *   ./gradlew reboundPing
 */
abstract class ReboundCliTask : DefaultTask() {

    @get:Input
    @get:Option(option = "command", description = "Command to send: snapshot, summary, telemetry, ping")
    var command: String = "snapshot"

    @get:Input
    @get:Option(option = "port", description = "Local TCP port (default 18462)")
    var port: Int = PORT

    init {
        group = "rebound"
    }

    @TaskAction
    fun execute() {
        if (!connect()) {
            logger.error("Rebound: No connection — is the app running with Rebound enabled?")
            logger.error("Rebound: Android: device/emulator connected, debug app launched")
            logger.error("Rebound: iOS: run 'iproxy $port $port' first, then launch the app")
            return
        }

        val result = sendCommand(command)
        if (result.isNullOrBlank()) {
            logger.error("Rebound: No response for '$command'. Is the app running?")
        } else {
            println(result)
        }
    }

    /**
     * Try to connect to a running Rebound server.
     * 1. Direct TCP (works for iOS via iproxy, or simulator, or any pre-forwarded port)
     * 2. ADB forward (Android: abstract socket → TCP)
     */
    private fun connect(): Boolean {
        // Try direct TCP first — covers iOS (iproxy), simulator, already-forwarded
        if (sendCommand("ping")?.trim() == "pong") return true

        // Fall back to ADB forward for Android
        return setupAdbForward()
    }

    private fun setupAdbForward(): Boolean {
        // Try canonical socket name first
        if (tryAdbForward("rebound")) return true

        // Discover PID-suffixed fallback sockets (rebound_<pid>)
        val unixSockets = adb("shell", "cat /proc/net/unix 2>/dev/null") ?: return false
        val pattern = Regex("@(rebound_\\d+)")
        for (match in pattern.findAll(unixSockets)) {
            val sockName = match.groupValues[1]
            if (tryAdbForward(sockName)) return true
        }
        return false
    }

    private fun tryAdbForward(socketName: String): Boolean {
        adb("forward", "tcp:$port", "localabstract:$socketName") ?: return false
        val pong = sendCommand("ping")
        return pong?.trim() == "pong"
    }

    private fun sendCommand(cmd: String): String? {
        return try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("$cmd\n")
                writer.flush()
                // Give the server time to process before shutting down output
                Thread.sleep(500)
                socket.shutdownOutput()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                reader.readText()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun adb(vararg args: String): String? {
        return try {
            val adbPath = findAdb()
            val process = ProcessBuilder(listOf(adbPath) + args.toList())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (e: Exception) {
            null
        }
    }

    private fun findAdb(): String {
        // Try ANDROID_HOME/platform-tools/adb
        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val adb = java.io.File(androidHome, "platform-tools/adb")
            if (adb.exists()) return adb.absolutePath
        }
        // Try common macOS path
        val userAdb = java.io.File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
        if (userAdb.exists()) return userAdb.absolutePath
        // Fall back to PATH
        return "adb"
    }

    companion object {
        const val PORT = 18462
    }
}
