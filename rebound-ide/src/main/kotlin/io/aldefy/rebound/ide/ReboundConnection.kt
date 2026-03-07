package io.aldefy.rebound.ide

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket

/**
 * Connects to ReboundServer on the device via adb forward.
 *
 * Flow:
 * 1. Sets up adb forward: tcp:18462 → localabstract:rebound
 * 2. Polls every ~1s: connects, sends "snapshot", reads JSON, parses entries
 * 3. Calls onUpdate with parsed entries
 */
class ReboundConnection(
    private val onUpdate: (List<ComposableEntry>) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    private val log = Logger.getInstance(ReboundConnection::class.java)

    @Volatile
    private var running = false

    private var pollThread: Thread? = null

    companion object {
        private const val LOCAL_PORT = 18462
        private const val SOCKET_NAME = "rebound"
    }

    fun start() {
        if (running) return
        running = true

        pollThread = Thread({
            try {
                // Set up adb forward
                val forwardOk = setupAdbForward()
                log.warn("adb forward result: $forwardOk")
                if (!forwardOk) {
                    onError?.invoke("Failed to set up adb forward. Is a device connected?")
                    running = false
                    return@Thread
                }

                var failCount = 0
                while (running) {
                    try {
                        val json = sendCommand("snapshot")
                        if (json != null && json.contains("composables")) {
                            val entries = SnapshotParser.parse(json)
                            if (entries.isNotEmpty()) {
                                failCount = 0
                                onUpdate(entries)
                            }
                        } else {
                            failCount++
                            if (failCount >= 5) {
                                onError?.invoke("No response from app. Is ReboundServer running?")
                                failCount = 0
                            }
                        }
                    } catch (e: Exception) {
                        log.warn("Poll error: ${e.message}", e)
                        failCount++
                    }

                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
                // Stopped
            } catch (e: Exception) {
                val msg = "Connection error: ${e.message}"
                log.warn(msg, e)
                onError?.invoke(msg)
            } finally {
                removeAdbForward()
                running = false
            }
        }, "Rebound-Connection")
        pollThread?.isDaemon = true
        pollThread?.start()
    }

    fun stop() {
        running = false
        pollThread?.interrupt()
        pollThread = null
        removeAdbForward()
    }

    val isRunning: Boolean get() = running

    private fun sendCommand(command: String): String? {
        return try {
            val socket = Socket("localhost", LOCAL_PORT)
            socket.soTimeout = 3000
            val writer = OutputStreamWriter(socket.getOutputStream())
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.write("$command\n")
            writer.flush()
            // Read all content until server closes connection (do NOT shutdownOutput — kills the pipe)
            val response = reader.readText()
            socket.close()
            response.trim().ifEmpty { null }
        } catch (e: Exception) {
            log.debug("sendCommand($command) failed: ${e.message}")
            null
        }
    }

    private fun setupAdbForward(): Boolean {
        val adb = LogcatProcess.resolveAdb() ?: return false

        // Try canonical socket name first
        if (tryForward(adb, SOCKET_NAME)) return true

        // Canonical failed — discover PID-suffixed fallback sockets
        // List all adb-visible abstract sockets matching rebound_*
        try {
            val proc = ProcessBuilder(adb, "shell", "cat /proc/net/unix")
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()

            val pidSockets = output.lines()
                .filter { it.contains("@rebound_") }
                .mapNotNull { line ->
                    val match = "@(rebound_\\d+)".toRegex().find(line)
                    match?.groupValues?.get(1)
                }

            for (socketName in pidSockets) {
                log.warn("Trying fallback socket: $socketName")
                if (tryForward(adb, socketName)) return true
            }
        } catch (e: Exception) {
            log.warn("Socket discovery failed: ${e.message}")
        }

        return false
    }

    private fun tryForward(adb: String, socketName: String): Boolean {
        return try {
            val pb = ProcessBuilder(adb, "forward", "tcp:$LOCAL_PORT", "localabstract:$socketName")
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.errorStream.bufferedReader().readText()
                log.warn("adb forward to $socketName failed: $err")
            }
            exitCode == 0
        } catch (e: Exception) {
            log.warn("adb forward error ($socketName): ${e.message}")
            false
        }
    }

    private fun removeAdbForward() {
        val adb = LogcatProcess.resolveAdb() ?: return
        try {
            ProcessBuilder(adb, "forward", "--remove", "tcp:$LOCAL_PORT").start().waitFor()
        } catch (_: Exception) {}
    }
}
