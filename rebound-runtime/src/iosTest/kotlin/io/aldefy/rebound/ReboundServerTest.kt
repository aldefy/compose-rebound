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
import platform.Foundation.NSThread
import platform.posix.AF_INET
import platform.posix.INADDR_LOOPBACK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_SNDTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the iOS TCP server.
 * These run on the iOS simulator and test real socket connectivity.
 */
class ReboundServerTest {

    // Use unique ports to avoid EADDRINUSE (TIME_WAIT from prior tests)
    private var testPort: UShort = 30000u
    private fun nextPort(): UShort = testPort.also { testPort = (testPort + 1u).toUShort() }

    private fun htons(value: UShort): UShort {
        val v = value.toInt()
        return ((v shr 8) or ((v and 0xFF) shl 8)).toUShort()
    }

    private fun htonl(value: UInt): UInt {
        return ((value shr 24) or
                ((value shr 8) and 0xFF00u) or
                ((value shl 8) and 0xFF0000u) or
                (value shl 24))
    }

    /**
     * Connect to localhost:PORT, send a command, return the response.
     * Returns "CONNECT_FAILED:errno=N" or "RECV_FAILED:errno=N" on failure for diagnostics.
     */
    private fun sendCommand(command: String, port: UShort = ReboundServer.port): String? = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return "SOCKET_FAILED:errno=$errno"

        // Timeouts
        val timeout = alloc<timeval>()
        timeout.tv_sec = 3
        timeout.tv_usec = 0
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(port)
        addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)

        val connectResult = connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        if (connectResult < 0) {
            val e = errno
            close(fd)
            return "CONNECT_FAILED:errno=$e"
        }

        val cmdBytes = "$command\n".encodeToByteArray()
        val sent = cmdBytes.usePinned { pinned ->
            send(fd, pinned.addressOf(0), cmdBytes.size.convert(), 0)
        }
        if (sent <= 0) {
            val e = errno
            close(fd)
            return "SEND_FAILED:errno=$e,sent=$sent"
        }

        val buffer = ByteArray(4096)
        val bytesRead = buffer.usePinned { pinned ->
            recv(fd, pinned.addressOf(0), buffer.size.convert(), 0)
        }
        close(fd)

        if (bytesRead > 0) {
            buffer.decodeToString(0, bytesRead.toInt()).trim()
        } else {
            "RECV_FAILED:errno=$errno,bytesRead=$bytesRead"
        }
    }

    private fun startServerAndWait(): UShort {
        val p = nextPort()
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundServer.stop()
        usleep(100_000u)
        ReboundServer.port = p
        ReboundServer.start()
        // Wait up to 2s for the accept thread to signal ready
        var waited = 0
        while (!ReboundServer.isReady && waited < 2_000_000) {
            usleep(50_000u)
            waited += 50_000
        }
        assertTrue(ReboundServer.isReady, "Server not ready after ${waited/1000}ms on port $p, isRunning=${ReboundServer.isRunning}")
        return p
    }

    private fun stopServer() {
        ReboundServer.stop()
        usleep(100_000u) // cleanup
        ReboundTracker.enabled = true
    }

    /**
     * Bare-metal test: create a server socket, fork accept on NSThread,
     * connect from this thread, verify data round-trips.
     * This isolates whether the socket layer or the ReboundServer logic is broken.
     */
    @Test
    fun rawSocketRoundTrip() = memScoped {
        // Create server socket
        val serverFd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(serverFd >= 0, "socket() failed: errno=$errno")

        val optVal = intArrayOf(1)
        setsockopt(serverFd, SOL_SOCKET, platform.posix.SO_REUSEADDR, optVal.refTo(0), 4u.convert())

        val testPort: UShort = 19999u
        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(testPort)
        addr.sin_addr.s_addr = platform.posix.INADDR_ANY

        val bindResult = platform.posix.bind(
            serverFd,
            addr.ptr.reinterpret<sockaddr>(),
            sizeOf<sockaddr_in>().convert()
        )
        assertTrue(bindResult == 0, "bind() failed: errno=$errno")
        assertTrue(platform.posix.listen(serverFd, 5) == 0, "listen() failed: errno=$errno")

        // Accept on NSThread
        var acceptedFd = -1
        var acceptError = -1
        val acceptThread = object : platform.Foundation.NSThread() {
            override fun main() {
                acceptedFd = platform.posix.accept(serverFd, null, null)
                if (acceptedFd < 0) acceptError = errno
                if (acceptedFd >= 0) {
                    // Echo back whatever we receive
                    val buf = ByteArray(256)
                    val n = buf.usePinned { p ->
                        recv(acceptedFd, p.addressOf(0), buf.size.convert(), 0)
                    }
                    if (n > 0) {
                        buf.usePinned { p ->
                            send(acceptedFd, p.addressOf(0), n.convert(), 0)
                        }
                    }
                    close(acceptedFd)
                }
            }
        }
        acceptThread.name = "RawSocketTest"
        acceptThread.start()
        usleep(200_000u) // 200ms for thread to start and call accept()

        // Connect from test thread
        val clientFd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(clientFd >= 0, "client socket() failed")

        val timeout = alloc<timeval>()
        timeout.tv_sec = 3
        timeout.tv_usec = 0
        setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())

        val clientAddr = alloc<sockaddr_in>()
        clientAddr.sin_len = sizeOf<sockaddr_in>().toUByte()
        clientAddr.sin_family = AF_INET.convert()
        clientAddr.sin_port = htons(testPort)
        clientAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)

        val connectResult = connect(clientFd, clientAddr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        assertTrue(connectResult == 0, "connect() failed: errno=$errno")

        // Send and receive
        val msg = "hello\n".encodeToByteArray()
        val sent = msg.usePinned { p -> send(clientFd, p.addressOf(0), msg.size.convert(), 0) }
        assertTrue(sent > 0, "send() failed: errno=$errno")

        val recvBuf = ByteArray(256)
        val received = recvBuf.usePinned { p -> recv(clientFd, p.addressOf(0), recvBuf.size.convert(), 0) }
        close(clientFd)

        // Wait for accept thread to finish
        usleep(200_000u)
        close(serverFd)

        assertTrue(acceptedFd >= 0, "accept() failed: errno=$acceptError")
        assertTrue(received > 0, "recv() returned $received, errno=$errno")
        val response = recvBuf.decodeToString(0, received.toInt()).trim()
        assertEquals("hello", response, "Echo server should return 'hello', got '$response'")
    }

    /**
     * Reproduce the exact handleClient code path on a raw socket.
     * If this passes, the bug is in server setup. If it fails, the bug is in handleClient logic.
     */
    @Test
    fun handleClientCodePathWorks() = memScoped {
        val testPort: UShort = 19998u

        // Create server
        val serverFd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(serverFd >= 0)
        val optVal = intArrayOf(1)
        setsockopt(serverFd, SOL_SOCKET, platform.posix.SO_REUSEADDR, optVal.refTo(0), 4u.convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(testPort)
        addr.sin_addr.s_addr = platform.posix.INADDR_ANY
        platform.posix.bind(serverFd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        platform.posix.listen(serverFd, 5)

        // Server thread — mimics handleClient exactly
        var serverRecvBytes: Long = -999
        var serverRecvErrno: Int = 0
        var serverSendBytes: Long = -999
        var serverCommand = ""
        val serverThread = object : NSThread() {
            override fun main() {
                val clientFd = platform.posix.accept(serverFd, null, null)
                if (clientFd < 0) return

                // Exact handleClient code:
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
                serverRecvBytes = bytesRead
                if (bytesRead <= 0) {
                    serverRecvErrno = errno
                    close(clientFd)
                    return
                }

                serverCommand = buffer.decodeToString(0, bytesRead.toInt()).trim()

                val response = "pong\n".encodeToByteArray()
                val bytesSent = response.usePinned { pinned ->
                    send(clientFd, pinned.addressOf(0), response.size.convert(), 0)
                }
                serverSendBytes = bytesSent
                close(clientFd)
            }
        }
        serverThread.start()
        usleep(200_000u)

        // Client
        val clientFd = socket(AF_INET, SOCK_STREAM, 0)
        val clientAddr = alloc<sockaddr_in>()
        clientAddr.sin_len = sizeOf<sockaddr_in>().toUByte()
        clientAddr.sin_family = AF_INET.convert()
        clientAddr.sin_port = htons(testPort)
        clientAddr.sin_addr.s_addr = htonl(INADDR_LOOPBACK)

        val timeout = alloc<timeval>()
        timeout.tv_sec = 3
        timeout.tv_usec = 0
        setsockopt(clientFd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())

        val cr = connect(clientFd, clientAddr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        assertTrue(cr == 0, "connect failed: errno=$errno")

        val msg = "ping\n".encodeToByteArray()
        msg.usePinned { p -> send(clientFd, p.addressOf(0), msg.size.convert(), 0) }

        val buf = ByteArray(256)
        val received = buf.usePinned { p -> recv(clientFd, p.addressOf(0), buf.size.convert(), 0) }
        close(clientFd)
        usleep(200_000u)
        close(serverFd)

        assertTrue(serverRecvBytes > 0, "Server recv=$serverRecvBytes, errno=$serverRecvErrno")
        assertEquals("ping", serverCommand, "Server should have received 'ping'")
        assertTrue(serverSendBytes > 0, "Server send=$serverSendBytes")
        assertTrue(received > 0, "Client recv=$received, errno=$errno")
        assertEquals("pong", buf.decodeToString(0, received.toInt()).trim())
    }

    @Test
    fun pingReturnsPong() {
        startServerAndWait()
        try {
            val response = sendCommand("ping")
            assertNotNull(response, "sendCommand returned null — impossible with new diagnostics")
            assertEquals("pong", response, "Expected 'pong' but got '$response'")
        } finally {
            stopServer()
        }
    }

    @Test
    fun snapshotReturnsValidJson() {
        startServerAndWait()
        try {
            // Record some compositions first
            ReboundTracker.onComposition("TestScreen", BudgetClass.SCREEN.ordinal, 0, "")

            val response = sendCommand("snapshot")
            assertNotNull(response, "Server should respond to snapshot")
            assertTrue(response.contains("TestScreen"), "Snapshot should contain tracked composable")
            assertTrue(response.startsWith("{"), "Snapshot should be JSON")
        } finally {
            stopServer()
        }
    }

    @Test
    fun summaryReturnsValidJson() {
        startServerAndWait()
        try {
            ReboundTracker.onComposition("SummaryTest", BudgetClass.LEAF.ordinal, 0, "")

            val response = sendCommand("summary")
            assertNotNull(response, "Server should respond to summary")
            assertTrue(response.contains("composables"), "Summary should contain composables key")
            assertTrue(response.contains("violations"), "Summary should contain violations key")
        } finally {
            stopServer()
        }
    }

    @Test
    fun unknownCommandReturnsError() {
        startServerAndWait()
        try {
            val response = sendCommand("badcommand")
            assertNotNull(response, "Server should respond to unknown commands")
            assertTrue(response.contains("error"), "Unknown command should return error")
        } finally {
            stopServer()
        }
    }

    @Test
    fun multipleConnectionsWork() {
        startServerAndWait()
        try {
            repeat(5) { i ->
                val response = sendCommand("ping")
                assertNotNull(response, "Connection $i should succeed")
                assertEquals("pong", response, "Connection $i should return pong")
            }
        } finally {
            stopServer()
        }
    }

    @Test
    fun serverRestartsCleanly() {
        startServerAndWait()
        val r1 = sendCommand("ping")
        assertEquals("pong", r1)

        // Stop and restart
        ReboundServer.stop()
        usleep(200_000u)
        ReboundServer.start()
        usleep(500_000u)

        val r2 = sendCommand("ping")
        assertNotNull(r2, "Server should respond after restart")
        assertEquals("pong", r2)

        stopServer()
    }

    @Test
    fun isSimulatorReturnsTrueInTestEnvironment() {
        // Tests always run on the simulator, so this should be true
        assertTrue(ReboundServer.isSimulator(), "isSimulator() should return true in test environment (simulator)")
    }

    @Test
    fun buildSummaryProducesValidJson() {
        // Verify the structured JSON output used by console mode
        ReboundTracker.reset()
        ReboundTracker.enabled = true

        ReboundTracker.onComposition("ConsoleTest.Screen", BudgetClass.SCREEN.ordinal, 0, "")
        ReboundTracker.onComposition("ConsoleTest.Button", BudgetClass.LEAF.ordinal, 0, "")

        val summary = ReboundServer.buildSummary()
        assertTrue(summary.startsWith("{"), "Summary should be valid JSON, got: $summary")
        assertTrue(summary.contains("\"composables\""), "Summary should contain composables key")
        assertTrue(summary.contains("\"violations\""), "Summary should contain violations key")
        assertTrue(summary.contains("\"total\""), "Summary should contain total key")
        assertTrue(summary.contains("Screen"), "Summary should include tracked composable")
    }

    @Test
    fun snapshotJsonIsGrepable() {
        // Verify the snapshot JSON can be prefixed with [Rebound:snapshot] for console grep
        ReboundTracker.reset()
        ReboundTracker.enabled = true
        ReboundTracker.onComposition("GrepTest", BudgetClass.LEAF.ordinal, 0, "")

        val snapshot = ReboundTracker.toJson()
        val tagged = "[Rebound:snapshot] $snapshot"
        assertTrue(tagged.startsWith("[Rebound:snapshot] {"), "Tagged snapshot should be grep-able")

        // Verify extracting JSON after prefix
        val extracted = tagged.substringAfter("[Rebound:snapshot] ")
        assertTrue(extracted.startsWith("{"), "Extracted JSON should start with {")
        assertTrue(extracted.contains("GrepTest"), "Extracted JSON should contain tracked composable")
    }

    @Test
    fun telemetryReturnsValidJson() {
        startServerAndWait()
        try {
            ReboundTracker.onComposition("TelTest", BudgetClass.SCREEN.ordinal, 0, "")
            ReboundTelemetry.enabled = true

            val response = sendCommand("telemetry")
            assertNotNull(response, "Server should respond to telemetry")
            assertTrue(response.contains("composableCount"), "Telemetry should contain composableCount")

            ReboundTelemetry.enabled = false
        } finally {
            stopServer()
        }
    }
}
