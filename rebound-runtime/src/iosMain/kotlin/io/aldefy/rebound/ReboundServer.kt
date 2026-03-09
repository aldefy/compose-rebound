@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aldefy.rebound

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.INADDR_ANY
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDTIMEO
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.listen
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.usleep
import kotlin.concurrent.AtomicInt

object ReboundServer {

    internal const val DEFAULT_PORT: UShort = 18462u
    internal var port: UShort = DEFAULT_PORT
    private const val BACKLOG = 5
    /** Interval between console-mode JSON snapshots (microseconds). Default: 2 seconds. */
    var consoleIntervalUs: UInt = 2_000_000u

    private val runningState = AtomicInt(0)
    private val serverFdHolder = AtomicInt(-1)
    // Signals that the accept loop has started blocking on accept()
    private val readyState = AtomicInt(0)

    private val running: Boolean get() = runningState.value == 1
    val isRunning: Boolean get() = running
    val isReady: Boolean get() = readyState.value == 1

    internal fun isSimulator(): Boolean =
        NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

    internal fun htons(value: UShort): UShort {
        val v = value.toInt()
        return ((v shr 8) or ((v and 0xFF) shl 8)).toUShort()
    }

    fun start() {
        if (!runningState.compareAndSet(0, 1)) return
        readyState.value = 0

        if (isSimulator()) {
            startTcpServer()
        } else {
            startConsoleMode()
        }
    }

    private fun startTcpServer() {
        val fd = createServerSocket()
        if (fd < 0) {
            ReboundLogger.warn("Rebound", "Server failed to bind on port $port")
            runningState.value = 0
            return
        }
        serverFdHolder.value = fd
        ReboundLogger.log("Rebound", "Server listening on 0.0.0.0:$port (simulator)")

        val serverFd = fd
        val thread = object : NSThread() {
            override fun main() {
                ReboundLogger.log("Rebound", "Accept thread started (fd=$serverFd)")
                readyState.value = 1

                while (runningState.value == 1) {
                    val clientFd = accept(serverFd, null, null)
                    if (clientFd < 0) {
                        if (runningState.value != 1) break
                        ReboundLogger.warn("Rebound", "accept() error: errno=$errno")
                        continue
                    }
                    ReboundLogger.log("Rebound", "Client connected (fd=$clientFd)")
                    handleClient(clientFd)
                }
                ReboundLogger.log("Rebound", "Accept loop exited")
            }
        }
        thread.name = "Rebound-Server"
        thread.start()
    }

    private fun startConsoleMode() {
        ReboundLogger.log("Rebound", "Console mode (physical device) — structured logs every ${consoleIntervalUs / 1_000_000u}s")
        readyState.value = 1

        val thread = object : NSThread() {
            override fun main() {
                while (runningState.value == 1) {
                    val snapshot = ReboundTracker.toJson()
                    ReboundLogger.log("Rebound:snapshot", snapshot)

                    val summary = buildSummary()
                    ReboundLogger.log("Rebound:summary", summary)

                    usleep(consoleIntervalUs)
                }
                ReboundLogger.log("Rebound", "Console mode stopped")
            }
        }
        thread.name = "Rebound-Console"
        thread.start()
    }

    fun stop() {
        runningState.value = 0
        readyState.value = 0
        val fd = serverFdHolder.value
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR)
            close(fd)
            serverFdHolder.value = -1
        }
    }

    private fun createServerSocket(): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) {
            ReboundLogger.warn("Rebound", "socket() failed, errno=$errno")
            return -1
        }

        val optVal = intArrayOf(1)
        setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, optVal.refTo(0), 4u.convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port)
        addr.sin_addr.s_addr = INADDR_ANY

        if (bind(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert()) < 0) {
            ReboundLogger.warn("Rebound", "bind() failed, errno=$errno")
            close(fd)
            return -1
        }

        if (listen(fd, BACKLOG) < 0) {
            ReboundLogger.warn("Rebound", "listen() failed, errno=$errno")
            close(fd)
            return -1
        }

        fd
    }

    // Made internal for testing; called from NSThread
    internal fun acceptLoop() {
        acceptLoopDirect(serverFdHolder.value)
    }

    private fun acceptLoopDirect(serverFd: Int) {
        ReboundLogger.log("Rebound", "acceptLoopDirect (fd=$serverFd)")
        readyState.value = 1

        try {
            while (running) {
                val clientFd = accept(serverFd, null, null)
                if (clientFd < 0) {
                    if (!running) break
                    continue
                }
                handleClient(clientFd)
            }
        } catch (e: Exception) {
            ReboundLogger.warn("Rebound", "Accept loop error: ${e.message}")
        }
    }

    private fun handleClient(clientFd: Int) {
        try {
            memScoped {
                val timeout = alloc<timeval>()
                timeout.tv_sec = 2
                timeout.tv_usec = 0
                setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
                setsockopt(clientFd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
            }

            val buffer = ByteArray(1024)
            val bytesRead = buffer.usePinned { pinned ->
                recv(clientFd, pinned.addressOf(0), buffer.size.convert(), 0)
            }
            if (bytesRead <= 0) {
                ReboundLogger.warn("Rebound", "recv=$bytesRead errno=$errno")
                return
            }

            val command = buffer.decodeToString(0, bytesRead.toInt()).trim()
            ReboundLogger.log("Rebound", "Command: '$command'")

            val response = when (command) {
                "ping" -> "pong"
                "snapshot" -> ReboundTracker.toJson()
                "summary" -> buildSummary()
                "telemetry" -> ReboundTelemetry.generateReport().toJson()
                else -> """{"error":"unknown command: $command"}"""
            }

            val responseBytes = (response + "\n").encodeToByteArray()
            responseBytes.usePinned { pinned ->
                send(clientFd, pinned.addressOf(0), responseBytes.size.convert(), 0)
            }
        } catch (e: Exception) {
            ReboundLogger.warn("Rebound", "handleClient error: ${e.message}")
        } finally {
            close(clientFd)
        }
    }

    internal fun buildSummary(): String {
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
