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
import kotlin.random.Random

object ReboundServer {

    internal const val DEFAULT_PORT: UShort = 18462u
    private const val WS_PORT: UShort = 18463u
    internal var port: UShort = DEFAULT_PORT
    private const val BACKLOG = 5
    /** Interval between console-mode JSON snapshots (microseconds). Default: 2 seconds. */
    var consoleIntervalUs: UInt = 2_000_000u

    private val runningState = AtomicInt(0)
    private val serverFdHolder = AtomicInt(-1)
    private val wsFdHolder = AtomicInt(-1)
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
            startDeviceMode()
        }
    }

    // --- Shared command handler ---

    internal fun handleCommand(command: String): String {
        return when (command.trim()) {
            "ping" -> "pong"
            "snapshot" -> ReboundTracker.toJson()
            "summary" -> buildSummary()
            "telemetry" -> ReboundTelemetry.generateReport().toJson()
            else -> """{"error":"unknown command: $command"}"""
        }
    }

    // --- TCP Server (simulator) ---

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

    // --- Device mode: WebSocket → console fallback ---

    private fun startDeviceMode() {
        ReboundLogger.log("Rebound", "Device mode — discovering relay via Bonjour...")
        discoverRelay { host, port ->
            if (host != null && port > 0u) {
                startWebSocketClient(host, port)
            } else {
                ReboundLogger.log("Rebound", "No relay found, falling back to console mode")
                startConsoleMode()
            }
        }
    }

    private fun discoverRelay(callback: (String?, UShort) -> Unit) {
        // Check env var override first
        val envHost = NSProcessInfo.processInfo.environment["REBOUND_RELAY_HOST"] as? String
        if (envHost != null) {
            val parts = envHost.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toUShortOrNull() ?: WS_PORT
            ReboundLogger.log("Rebound", "Using relay from env: $host:$port")
            callback(host, port)
            return
        }

        // Bonjour discovery with 3s timeout
        BonjourDiscovery.discover(
            type = "_rebound._tcp",
            domain = "local.",
            timeoutUs = 3_000_000u,
            onFound = { host, port ->
                ReboundLogger.log("Rebound", "Relay discovered via Bonjour: $host:$port")
                callback(host, port)
            },
            onTimeout = {
                callback(null, 0u)
            }
        )
    }

    private fun startWebSocketClient(host: String, wsPort: UShort) {
        ReboundLogger.log("Rebound", "Connecting to relay $host:$wsPort via WebSocket")

        val thread = object : NSThread() {
            override fun main() {
                var backoffUs: UInt = 1_000_000u // 1s initial
                val maxBackoffUs: UInt = 10_000_000u // 10s max

                while (runningState.value == 1) {
                    val fd = connectTcp(host, wsPort)
                    if (fd < 0) {
                        ReboundLogger.warn("Rebound", "TCP connect to relay failed, retry in ${backoffUs / 1_000_000u}s")
                        usleep(backoffUs)
                        backoffUs = minOf(backoffUs * 2u, maxBackoffUs)
                        continue
                    }

                    val upgraded = performWebSocketHandshake(fd)
                    if (!upgraded) {
                        ReboundLogger.warn("Rebound", "WebSocket handshake failed")
                        close(fd)
                        usleep(backoffUs)
                        backoffUs = minOf(backoffUs * 2u, maxBackoffUs)
                        continue
                    }

                    wsFdHolder.value = fd
                    readyState.value = 1
                    backoffUs = 1_000_000u // reset on success
                    ReboundLogger.log("Rebound", "WebSocket connected to relay")

                    // Frame read loop
                    webSocketFrameLoop(fd)

                    wsFdHolder.value = -1
                    readyState.value = 0
                    ReboundLogger.log("Rebound", "WebSocket disconnected, reconnecting...")
                }
            }
        }
        thread.name = "Rebound-WS"
        thread.start()
    }

    private fun connectTcp(host: String, targetPort: UShort): Int = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return -1

        val timeout = alloc<timeval>()
        timeout.tv_sec = 5
        timeout.tv_usec = 0
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, timeout.ptr, sizeOf<timeval>().convert())
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, timeout.ptr, sizeOf<timeval>().convert())

        val addr = alloc<sockaddr_in>()
        addr.sin_len = sizeOf<sockaddr_in>().toUByte()
        addr.sin_family = AF_INET.convert()
        addr.sin_port = htons(targetPort)

        // Resolve host — for simplicity, support IP addresses and "localhost"
        val ip = if (host == "localhost" || host == "127.0.0.1") {
            0x7F000001u // 127.0.0.1 in host byte order
        } else {
            // Parse dotted quad
            val parts = host.split(".")
            if (parts.size == 4) {
                parts.mapNotNull { it.toUIntOrNull() }.let { ints ->
                    if (ints.size == 4) {
                        (ints[0] shl 24) or (ints[1] shl 16) or (ints[2] shl 8) or ints[3]
                    } else null
                }
            } else null
        }

        if (ip == null) {
            close(fd)
            return -1
        }

        // Convert to network byte order
        addr.sin_addr.s_addr = htonl(ip)

        val result = platform.posix.connect(fd, addr.ptr.reinterpret<sockaddr>(), sizeOf<sockaddr_in>().convert())
        if (result < 0) {
            close(fd)
            return -1
        }

        fd
    }

    private fun htonl(value: UInt): UInt {
        return ((value shr 24) or
                ((value shr 8) and 0xFF00u) or
                ((value shl 8) and 0xFF0000u) or
                (value shl 24))
    }

    private fun performWebSocketHandshake(fd: Int): Boolean {
        // Generate random 16-byte key, base64-encode it
        val keyBytes = Random.nextBytes(16)
        val wsKey = base64Encode(keyBytes)

        val request = "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "\r\n"

        val requestBytes = request.encodeToByteArray()
        val sent = requestBytes.usePinned { pinned ->
            send(fd, pinned.addressOf(0), requestBytes.size.convert(), 0)
        }
        if (sent <= 0) return false

        // Read response
        val buffer = ByteArray(1024)
        val bytesRead = buffer.usePinned { pinned ->
            recv(fd, pinned.addressOf(0), buffer.size.convert(), 0)
        }
        if (bytesRead <= 0) return false

        val response = buffer.decodeToString(0, bytesRead.toInt())
        return response.contains("101") && response.contains("Upgrade", ignoreCase = true)
    }

    private fun webSocketFrameLoop(fd: Int) {
        var lastPingTime = currentTimeMs()
        val pingIntervalMs = 30_000L

        while (runningState.value == 1) {
            // Check if we should send a ping
            val now = currentTimeMs()
            if (now - lastPingTime > pingIntervalMs) {
                val pingFrame = WebSocketFrame.encodePingFrame()
                val sent = pingFrame.usePinned { pinned ->
                    send(fd, pinned.addressOf(0), pingFrame.size.convert(), 0)
                }
                if (sent <= 0) break
                lastPingTime = now
            }

            // Try to read a frame (with timeout set on the socket)
            val frame = WebSocketFrame.readFrame(fd) ?: break

            when (frame.first) {
                WebSocketFrame.OPCODE_TEXT -> {
                    val command = frame.second.decodeToString()
                    val response = handleCommand(command)
                    val responseFrame = WebSocketFrame.encodeTextFrame(response, masked = true)
                    val sent = responseFrame.usePinned { pinned ->
                        send(fd, pinned.addressOf(0), responseFrame.size.convert(), 0)
                    }
                    if (sent <= 0) break
                }
                WebSocketFrame.OPCODE_PING -> {
                    // Respond with pong (same payload)
                    val pongFrame = WebSocketFrame.encodePongFrame(frame.second)
                    pongFrame.usePinned { pinned ->
                        send(fd, pinned.addressOf(0), pongFrame.size.convert(), 0)
                    }
                }
                WebSocketFrame.OPCODE_CLOSE -> {
                    ReboundLogger.log("Rebound", "WebSocket close frame received")
                    break
                }
                else -> {} // ignore unknown opcodes
            }
        }

        close(fd)
    }

    private fun currentTimeMs(): Long {
        // Use posix gettimeofday for millisecond timestamp
        return memScoped {
            val tv = alloc<timeval>()
            platform.posix.gettimeofday(tv.ptr, null)
            tv.tv_sec * 1000L + tv.tv_usec / 1000L
        }
    }

    // --- Console mode (fallback for physical device without relay) ---

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
        val wsFd = wsFdHolder.value
        if (wsFd >= 0) {
            shutdown(wsFd, SHUT_RDWR)
            close(wsFd)
            wsFdHolder.value = -1
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

            val response = handleCommand(command)

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

// --- Base64 encoding (pure Kotlin, no Foundation dependency) ---

internal fun base64Encode(data: ByteArray): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder()
    var i = 0
    while (i < data.size) {
        val b0 = data[i].toInt() and 0xFF
        val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
        val remaining = data.size - i

        sb.append(chars[(b0 shr 2) and 0x3F])
        sb.append(chars[((b0 shl 4) or (b1 shr 4)) and 0x3F])
        if (remaining > 1) sb.append(chars[((b1 shl 2) or (b2 shr 6)) and 0x3F]) else sb.append('=')
        if (remaining > 2) sb.append(chars[b2 and 0x3F]) else sb.append('=')

        i += 3
    }
    return sb.toString()
}

// --- Bonjour Discovery ---

internal object BonjourDiscovery {
    fun discover(
        type: String,
        domain: String,
        timeoutUs: UInt,
        onFound: (String, UShort) -> Unit,
        onTimeout: () -> Unit
    ) {
        val thread = object : NSThread() {
            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun main() {
                val browser = platform.Foundation.NSNetServiceBrowser()
                val delegate = BonjourBrowserDelegate()
                browser.setDelegate(delegate)
                browser.searchForServicesOfType(type, inDomain = domain)

                // Poll with CFRunLoop until found or timeout
                var elapsedUs: UInt = 0u
                val pollIntervalUs: UInt = 100_000u // 100ms
                val pollIntervalSec = pollIntervalUs.toDouble() / 1_000_000.0
                while (!delegate.resolved && elapsedUs < timeoutUs) {
                    // Pump the run loop briefly for Bonjour callbacks
                    platform.CoreFoundation.CFRunLoopRunInMode(
                        platform.CoreFoundation.kCFRunLoopDefaultMode, pollIntervalSec, false
                    )
                    elapsedUs += pollIntervalUs
                }

                browser.stop()

                if (delegate.resolved) {
                    val host = delegate.resolvedHost!!
                    val port = delegate.resolvedPort
                    onFound(host, port)
                } else {
                    onTimeout()
                }
            }
        }
        thread.name = "Rebound-Bonjour"
        thread.start()
    }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
private class BonjourBrowserDelegate :
    platform.darwin.NSObject(),
    platform.Foundation.NSNetServiceBrowserDelegateProtocol,
    platform.Foundation.NSNetServiceDelegateProtocol {

    var resolved = false
    var resolvedHost: String? = null
    var resolvedPort: UShort = 0u

    // Retain the service to prevent deallocation during resolve
    private var pendingService: platform.Foundation.NSNetService? = null

    override fun netServiceBrowser(
        browser: platform.Foundation.NSNetServiceBrowser,
        didFindService: platform.Foundation.NSNetService,
        moreComing: Boolean
    ) {
        if (resolved) return
        ReboundLogger.log("Rebound", "Bonjour: found service ${didFindService.name}, resolving...")
        pendingService = didFindService
        didFindService.setDelegate(this)
        didFindService.resolveWithTimeout(5.0)
    }

    override fun netServiceDidResolveAddress(sender: platform.Foundation.NSNetService) {
        val host = sender.hostName ?: return
        val port = sender.port.toUShort()
        resolvedHost = host
        resolvedPort = port
        resolved = true
        ReboundLogger.log("Rebound", "Bonjour: resolved $host:$port")
    }

    override fun netService(
        sender: platform.Foundation.NSNetService,
        didNotResolve: Map<Any?, *>
    ) {
        ReboundLogger.warn("Rebound", "Bonjour: failed to resolve service")
    }
}

// --- WebSocket Frame Encoding/Decoding (RFC 6455) ---

internal object WebSocketFrame {
    const val OPCODE_TEXT: Byte = 0x1
    const val OPCODE_CLOSE: Byte = 0x8
    const val OPCODE_PING: Byte = 0x9
    const val OPCODE_PONG: Byte = 0xA

    fun encodeTextFrame(payload: String, masked: Boolean): ByteArray {
        val data = payload.encodeToByteArray()
        return encodeFrame(OPCODE_TEXT, data, masked)
    }

    fun encodePingFrame(payload: ByteArray = ByteArray(0)): ByteArray {
        return encodeFrame(OPCODE_PING, payload, masked = true)
    }

    fun encodePongFrame(payload: ByteArray): ByteArray {
        return encodeFrame(OPCODE_PONG, payload, masked = true)
    }

    fun encodeFrame(opcode: Byte, payload: ByteArray, masked: Boolean): ByteArray {
        val headerSize: Int
        val payloadLen = payload.size

        // Calculate header size
        val extLenSize = when {
            payloadLen <= 125 -> 0
            payloadLen <= 65535 -> 2
            else -> 8
        }
        val maskSize = if (masked) 4 else 0
        headerSize = 2 + extLenSize + maskSize

        val frame = ByteArray(headerSize + payloadLen)

        // Byte 0: FIN + opcode
        frame[0] = (0x80 or opcode.toInt()).toByte() // FIN = 1

        // Byte 1: MASK + payload length
        val maskBit = if (masked) 0x80 else 0
        when {
            payloadLen <= 125 -> {
                frame[1] = (maskBit or payloadLen).toByte()
            }
            payloadLen <= 65535 -> {
                frame[1] = (maskBit or 126).toByte()
                frame[2] = ((payloadLen shr 8) and 0xFF).toByte()
                frame[3] = (payloadLen and 0xFF).toByte()
            }
            else -> {
                frame[1] = (maskBit or 127).toByte()
                for (i in 0 until 8) {
                    frame[2 + i] = ((payloadLen shr ((7 - i) * 8)) and 0xFF).toByte()
                }
            }
        }

        val payloadOffset: Int
        if (masked) {
            val maskKey = Random.nextBytes(4)
            val maskOffset = 2 + extLenSize
            frame[maskOffset] = maskKey[0]
            frame[maskOffset + 1] = maskKey[1]
            frame[maskOffset + 2] = maskKey[2]
            frame[maskOffset + 3] = maskKey[3]
            payloadOffset = maskOffset + 4

            for (i in payload.indices) {
                frame[payloadOffset + i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        } else {
            payloadOffset = 2 + extLenSize
            payload.copyInto(frame, payloadOffset)
        }

        return frame
    }

    fun decodeFrame(data: ByteArray): Pair<Byte, ByteArray>? {
        if (data.size < 2) return null

        val opcode = (data[0].toInt() and 0x0F).toByte()
        val masked = (data[1].toInt() and 0x80) != 0
        var payloadLen = (data[1].toInt() and 0x7F).toLong()

        var offset = 2
        if (payloadLen == 126L) {
            if (data.size < 4) return null
            payloadLen = ((data[2].toInt() and 0xFF).toLong() shl 8) or
                    (data[3].toInt() and 0xFF).toLong()
            offset = 4
        } else if (payloadLen == 127L) {
            if (data.size < 10) return null
            payloadLen = 0
            for (i in 0 until 8) {
                payloadLen = (payloadLen shl 8) or (data[2 + i].toInt() and 0xFF).toLong()
            }
            offset = 10
        }

        val maskKey: ByteArray?
        if (masked) {
            if (data.size < offset + 4) return null
            maskKey = data.copyOfRange(offset, offset + 4)
            offset += 4
        } else {
            maskKey = null
        }

        if (data.size < offset + payloadLen.toInt()) return null
        val payload = data.copyOfRange(offset, offset + payloadLen.toInt())

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return Pair(opcode, payload)
    }

    fun readFrame(fd: Int): Pair<Byte, ByteArray>? {
        // Read header (2 bytes minimum)
        val header = readExact(fd, 2) ?: return null

        val opcode = (header[0].toInt() and 0x0F).toByte()
        val masked = (header[1].toInt() and 0x80) != 0
        var payloadLen = (header[1].toInt() and 0x7F).toLong()

        if (payloadLen == 126L) {
            val ext = readExact(fd, 2) ?: return null
            payloadLen = ((ext[0].toInt() and 0xFF).toLong() shl 8) or
                    (ext[1].toInt() and 0xFF).toLong()
        } else if (payloadLen == 127L) {
            val ext = readExact(fd, 8) ?: return null
            payloadLen = 0
            for (i in 0 until 8) {
                payloadLen = (payloadLen shl 8) or (ext[i].toInt() and 0xFF).toLong()
            }
        }

        val maskKey = if (masked) readExact(fd, 4) else null
        if (masked && maskKey == null) return null

        val payload = if (payloadLen > 0) {
            readExact(fd, payloadLen.toInt()) ?: return null
        } else {
            ByteArray(0)
        }

        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }

        return Pair(opcode, payload)
    }

    private fun readExact(fd: Int, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var totalRead = 0
        while (totalRead < count) {
            val n = buffer.usePinned { pinned ->
                recv(fd, pinned.addressOf(totalRead), (count - totalRead).convert(), 0)
            }
            if (n <= 0) return null
            totalRead += n.toInt()
        }
        return buffer
    }
}
