@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aldefy.rebound

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import kotlin.concurrent.AtomicInt

/**
 * TCP socket server for iOS that exposes ReboundTracker metrics to external tools
 * (IDE plugin, CLI).
 *
 * Protocol (identical to Android):
 *   Client connects to TCP 127.0.0.1:18462
 *   Client sends a line command, server responds with JSON + newline, then closes.
 *
 * Commands:
 *   "ping"       -> "pong"
 *   "snapshot"   -> full JSON snapshot
 *   "summary"    -> compact summary: top violators + stats
 *   "telemetry"  -> anonymized aggregate stats
 *
 * Connect from Mac via: iproxy 18462 18462
 * Then: echo "snapshot" | nc localhost 18462
 */
object ReboundServer {

    private const val PORT: UShort = 18462u
    private const val BACKLOG = 5

    // AtomicInt for thread-safe state: 0 = stopped, 1 = running
    private val runningState = AtomicInt(0)
    private val serverFdHolder = AtomicInt(-1)

    private val running: Boolean get() = runningState.value == 1

    fun start() {
        if (!runningState.compareAndSet(0, 1)) return

        val queue = dispatch_queue_create("io.aldefy.rebound.server", null)
        dispatch_async(queue) {
            try {
                val fd = createServerSocket()
                if (fd < 0) {
                    ReboundLogger.warn("Rebound", "Server failed to bind on port $PORT")
                    runningState.value = 0
                    return@dispatch_async
                }
                serverFdHolder.value = fd
                ReboundLogger.log("Rebound", "Server listening on 127.0.0.1:$PORT")

                while (running) {
                    val clientFd = accept(fd, null, null)
                    if (clientFd < 0) {
                        if (!running) break
                        continue
                    }
                    handleClient(clientFd)
                }
            } catch (e: Exception) {
                ReboundLogger.warn("Rebound", "Server error: ${e.message}")
            } finally {
                runningState.value = 0
            }
        }
    }

    fun stop() {
        runningState.value = 0
        val fd = serverFdHolder.value
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR)
            close(fd)
            serverFdHolder.value = -1
        }
    }

    val isRunning: Boolean get() = running

    /** Host-to-network byte order for 16-bit value. */
    private fun htons(value: UShort): UShort {
        val v = value.toInt()
        return ((v shr 8) or ((v and 0xFF) shl 8)).toUShort()
    }

    /** Host-to-network byte order for 32-bit value. */
    private fun htonl(value: UInt): UInt {
        return ((value shr 24) or
                ((value shr 8) and 0xFF00u) or
                ((value shl 8) and 0xFF0000u) or
                (value shl 24))
    }

    private fun createServerSocket(): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return -1

        // SO_REUSEADDR to avoid EADDRINUSE on restart
        val optVal = intArrayOf(1)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, optVal.refTo(0), 4u.convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(PORT)
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)

        val bindResult = bind(
            fd,
            addr.ptr.reinterpret<sockaddr>(),
            sizeOf<sockaddr_in>().convert()
        )
        if (bindResult < 0) {
            close(fd)
            return -1
        }

        if (listen(fd, BACKLOG) < 0) {
            close(fd)
            return -1
        }

        fd
    }

    private fun handleClient(clientFd: Int) {
        try {
            // Set receive timeout: 2 seconds
            memScoped {
                val timeout = alloc<timeval>()
                timeout.tv_sec = 2
                timeout.tv_usec = 0
                setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            }

            val buffer = ByteArray(1024)
            val bytesRead = recv(clientFd, buffer.refTo(0), buffer.size.convert(), 0)
            if (bytesRead <= 0) return

            val command = buffer.decodeToString(0, bytesRead.toInt()).trim()

            val response = when (command) {
                "ping" -> "pong"
                "snapshot" -> ReboundTracker.toJson()
                "summary" -> buildSummary()
                "telemetry" -> ReboundTelemetry.generateReport().toJson()
                else -> """{"error":"unknown command: $command"}"""
            }

            val responseBytes = (response + "\n").encodeToByteArray()
            send(clientFd, responseBytes.refTo(0), responseBytes.size.convert(), 0)
        } catch (_: Exception) {
            // Client disconnected or timeout
        } finally {
            close(clientFd)
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
