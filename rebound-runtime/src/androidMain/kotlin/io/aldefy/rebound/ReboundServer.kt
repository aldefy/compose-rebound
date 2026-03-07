package io.aldefy.rebound

import android.net.LocalServerSocket
import android.net.LocalSocket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Tiny local socket server that exposes ReboundTracker metrics to external tools
 * (IDE plugin, MCP server, CLI).
 *
 * Protocol:
 *   Client connects to abstract socket "rebound"
 *   Client sends a line command, server responds with JSON + newline, then closes.
 *
 * Commands:
 *   "snapshot"   → full JSON snapshot (same as ReboundTracker.toJson())
 *   "summary"    → compact summary: top violators + stats
 *   "telemetry"  → anonymized aggregate stats by budget class
 *   "ping"       → responds "pong" (health check)
 *
 * IDE plugin connects via: adb forward tcp:18462 localabstract:rebound
 * Then: echo "snapshot" | nc localhost 18462
 */
object ReboundServer {

    private const val SOCKET_PREFIX = "rebound"

    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: LocalServerSocket? = null

    private var serverThread: Thread? = null

    /** The actual socket name bound (includes PID for uniqueness). */
    var boundSocketName: String = SOCKET_PREFIX
        private set

    fun start() {
        if (running) return
        running = true

        serverThread = Thread({
            try {
                val server = bind()
                if (server == null) {
                    ReboundLogger.warn("Rebound", "Server failed to bind")
                    running = false
                    return@Thread
                }
                serverSocket = server
                ReboundLogger.log("Rebound", "Server bound to abstract socket: $boundSocketName")

                while (running) {
                    val client: LocalSocket = try {
                        server.accept()
                    } catch (_: Exception) {
                        if (!running) break
                        continue
                    }

                    // Handle each client on the accept thread (short-lived connections)
                    handleClient(client)
                }
            } catch (e: Exception) {
                ReboundLogger.warn("Rebound", "Server error: ${e.message}")
            } finally {
                running = false
            }
        }, "Rebound-Server")
        serverThread?.isDaemon = true
        serverThread?.start()
    }

    /**
     * Bind the abstract socket. Strategy:
     * 1. Try canonical name "rebound" (IDE/CLI expect this)
     * 2. If taken, probe it — if stale, retry after probe clears it
     * 3. If still taken (another live app), bind "rebound_<pid>" as fallback
     *
     * The IDE plugin and CLI use `adb forward localabstract:rebound`, so the
     * canonical name is preferred. The PID-suffixed fallback ensures the server
     * always starts even when the canonical name is stuck.
     */
    private fun bind(): LocalServerSocket? {
        // 1. Try canonical name
        try {
            boundSocketName = SOCKET_PREFIX
            return LocalServerSocket(SOCKET_PREFIX)
        } catch (_: Exception) { }

        // 2. Probe — connect to the existing socket to check if it's alive
        val isAlive = probeSocket(SOCKET_PREFIX)
        if (!isAlive) {
            // Stale socket — retry canonical name after brief delay
            repeat(3) { attempt ->
                try {
                    Thread.sleep(300L * (attempt + 1))
                    boundSocketName = SOCKET_PREFIX
                    return LocalServerSocket(SOCKET_PREFIX)
                } catch (_: Exception) { }
            }
        }

        // 3. Fallback: PID-suffixed name (always works, one socket per process)
        val pid = android.os.Process.myPid()
        val fallbackName = "${SOCKET_PREFIX}_$pid"
        return try {
            boundSocketName = fallbackName
            ReboundLogger.log("Rebound", "Canonical socket taken, using fallback: $fallbackName")
            LocalServerSocket(fallbackName)
        } catch (e: Exception) {
            ReboundLogger.warn("Rebound", "Fallback bind failed: ${e.message}")
            null
        }
    }

    /** Probe an abstract socket to check if a live server responds. */
    private fun probeSocket(name: String): Boolean {
        return try {
            val probe = LocalSocket()
            probe.connect(android.net.LocalSocketAddress(name))
            probe.soTimeout = 500
            val writer = OutputStreamWriter(probe.outputStream)
            val reader = BufferedReader(InputStreamReader(probe.inputStream))
            writer.write("ping\n")
            writer.flush()
            val response = try { reader.readLine() } catch (_: Exception) { null }
            probe.close()
            response == "pong"
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        serverThread = null
    }

    val isRunning: Boolean get() = running

    private fun handleClient(client: LocalSocket) {
        try {
            client.soTimeout = 2000
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val writer = OutputStreamWriter(client.outputStream)

            val command = reader.readLine()?.trim() ?: return

            val response = when (command) {
                "ping" -> "pong"
                "snapshot" -> ReboundTracker.toJson()
                "summary" -> buildSummary()
                "telemetry" -> ReboundTelemetry.generateReport().toJson()
                else -> """{"error":"unknown command: $command"}"""
            }

            writer.write(response)
            writer.write("\n")
            writer.flush()
        } catch (_: Exception) {
            // Client disconnected or timeout — ignore
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun buildSummary(): String {
        val snap = ReboundTracker.snapshot()
        if (snap.isEmpty()) return """{"composables":[],"violations":0}"""

        val violations = snap.count { (_, m) ->
            m.currentRate() > m.budgetClass.baseBudgetPerSecond
        }
        val topByRate = snap.entries
            .sortedByDescending { it.value.currentRate() }
            .take(10)
            .joinToString(",") { (key, m) ->
                val name = key.substringAfterLast('.')
                val skipPct = if (m.totalEnters > 0) (m.skipRate * 1000).toInt() / 10.0 else 0.0
                """{"name":"$name","fqn":"$key","rate":${m.currentRate()},"budget":${m.budgetClass.baseBudgetPerSecond},"class":"${m.budgetClass}","skip":$skipPct,"peak":${m.peakRate()},"total":${m.totalCount},"forced":${m.forcedRecompositionCount},"over":${m.currentRate() > m.budgetClass.baseBudgetPerSecond}}"""
            }

        return """{"composables":[$topByRate],"violations":$violations,"total":${snap.size}}"""
    }
}
