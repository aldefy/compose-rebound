package io.aldefy.rebound.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Saves a live Rebound snapshot to a JSON file for later diffing.
 *
 * Usage:
 *   ./gradlew reboundSave                          # auto-named: .rebound/snapshot-20260310-143022.json
 *   ./gradlew reboundSave -Ptag=before              # named: .rebound/before.json
 *   ./gradlew reboundSave -Ptag=before -Pdir=build  # named: build/before.json
 *   ./gradlew reboundSave -Pport=19999              # custom port
 */
abstract class ReboundSaveTask : DefaultTask() {

    init {
        group = "rebound"
    }

    @TaskAction
    fun execute() {
        val tag = project.findProperty("tag")?.toString()
        val dir = project.findProperty("dir")?.toString() ?: ".rebound"
        val port = project.findProperty("port")?.toString()?.toIntOrNull() ?: PORT

        if (!connect(port)) {
            logger.error("Rebound: No connection — is the app running with Rebound enabled?")
            return
        }

        val json = sendCommand("snapshot", port)
        if (json.isNullOrBlank()) {
            logger.error("Rebound: No response. Is the app running?")
            return
        }

        val outDir = File(project.projectDir, dir)
        outDir.mkdirs()

        val fileName = if (tag != null) {
            "$tag.json"
        } else {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
            "snapshot-$ts.json"
        }
        val outFile = File(outDir, fileName)
        outFile.writeText(json)

        // Count composables for feedback
        val count = "\"budgetClass\"".toRegex().findAll(json).count()
        println("Saved $count composables to ${outFile.relativeTo(project.projectDir)}")
    }

    private fun connect(port: Int): Boolean {
        if (sendCommand("ping", port)?.trim() == "pong") return true
        return setupAdbForward(port)
    }

    private fun setupAdbForward(port: Int): Boolean {
        if (tryAdbForward("rebound", port)) return true
        val unixSockets = adb("shell", "cat /proc/net/unix 2>/dev/null") ?: return false
        val pattern = Regex("@(rebound_\\d+)")
        for (match in pattern.findAll(unixSockets)) {
            if (tryAdbForward(match.groupValues[1], port)) return true
        }
        return false
    }

    private fun tryAdbForward(socketName: String, port: Int): Boolean {
        adb("forward", "tcp:$port", "localabstract:$socketName") ?: return false
        return sendCommand("ping", port)?.trim() == "pong"
    }

    private fun sendCommand(cmd: String, port: Int): String? {
        return try {
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 5000
                val writer = OutputStreamWriter(socket.getOutputStream())
                writer.write("$cmd\n")
                writer.flush()
                Thread.sleep(500)
                socket.shutdownOutput()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                reader.readText()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun adb(vararg args: String): String? {
        return try {
            val process = ProcessBuilder(listOf(findAdb()) + args.toList())
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() == 0) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun findAdb(): String {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (androidHome != null) {
            val adb = File(androidHome, "platform-tools/adb")
            if (adb.exists()) return adb.absolutePath
        }
        val userAdb = File(System.getProperty("user.home"), "Library/Android/sdk/platform-tools/adb")
        if (userAdb.exists()) return userAdb.absolutePath
        return "adb"
    }

    companion object {
        const val PORT = 18462
    }
}
