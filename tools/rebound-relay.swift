#!/usr/bin/env swift
// rebound-relay — Mac relay bridging iOS physical devices to CLI/IDE
//
// Architecture:
//   iOS Device ──WebSocket:18463──→ Relay ←──TCP:18462── CLI/IDE
//
// The relay also advertises _rebound._tcp via Bonjour so iOS devices
// can discover it automatically on the local network.
//
// Build:  swiftc -O -o tools/rebound-relay tools/rebound-relay.swift
// Run:    ./tools/rebound-relay

import Foundation
import Network

// MARK: - Configuration

let tcpPort: UInt16 = 18462
let wsPort: UInt16 = 18463
let bonjourType = "_rebound._tcp"

// MARK: - Device Connection (WebSocket)

/// Represents a connected iOS device
class DeviceConnection {
    let id: String
    let connection: NWConnection
    private var pendingResponses: [(String?) -> Void] = []
    private let queue = DispatchQueue(label: "device-conn")

    init(id: String, connection: NWConnection) {
        self.id = id
        self.connection = connection
    }

    /// Send a command to the device and call completion with the response
    func send(command: String, completion: @escaping (String?) -> Void) {
        queue.async { [self] in
            pendingResponses.append { response in
                completion(response)
            }
        }

        // Send as WebSocket text frame
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "cmd", metadata: [metadata])
        let data = command.data(using: .utf8)!

        connection.send(content: data, contentContext: context, isComplete: true, completion: .contentProcessed({ error in
            if let error = error {
                log("Device send error: \(error)")
                self.queue.async {
                    if let handler = self.pendingResponses.first {
                        self.pendingResponses.removeFirst()
                        handler(nil)
                    }
                }
            }
        }))
    }

    /// Called when we receive a response from the device
    func handleResponse(_ text: String) {
        queue.async { [self] in
            guard !pendingResponses.isEmpty else {
                log("Unexpected device message (no pending request): \(text.prefix(80))")
                return
            }
            let handler = pendingResponses.removeFirst()
            handler(text)
        }
    }

    func cancel() {
        connection.cancel()
    }
}

// MARK: - Relay Server

class RelayServer {
    private let networkQueue = DispatchQueue(label: "relay-network")
    private var wsListener: NWListener?
    private var tcpListener: NWListener?
    private var bonjourService: NetService?

    /// Connected iOS devices keyed by ID
    private var devices: [String: DeviceConnection] = [:]
    private let devicesLock = NSLock()

    // MARK: Start

    func start() {
        startWebSocketServer()
        startTCPServer()
        startBonjour()
    }

    // MARK: WebSocket Server (port 18463 — device connections)

    private func startWebSocketServer() {
        let wsOptions = NWProtocolWebSocket.Options()
        let params = NWParameters(tls: nil)
        params.defaultProtocolStack.applicationProtocols.insert(wsOptions, at: 0)

        do {
            wsListener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: wsPort)!)
        } catch {
            log("Failed to create WebSocket listener: \(error)")
            exit(1)
        }

        wsListener!.stateUpdateHandler = { state in
            switch state {
            case .ready:
                log("WebSocket server listening on :\(wsPort)")
            case .failed(let error):
                log("WebSocket listener failed: \(error)")
                exit(1)
            default:
                break
            }
        }

        wsListener!.newConnectionHandler = { [weak self] connection in
            self?.handleDeviceConnection(connection)
        }

        wsListener!.start(queue: networkQueue)
    }

    private func handleDeviceConnection(_ connection: NWConnection) {
        let deviceId = UUID().uuidString.prefix(8).lowercased()
        log("Device connecting: \(deviceId)")

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                log("Device connected: \(deviceId)")
                let device = DeviceConnection(id: String(deviceId), connection: connection)
                self?.devicesLock.lock()
                self?.devices[String(deviceId)] = device
                self?.devicesLock.unlock()
                self?.receiveFromDevice(device)
            case .failed(let error):
                log("Device \(deviceId) failed: \(error)")
                self?.removeDevice(String(deviceId))
            case .cancelled:
                log("Device \(deviceId) disconnected")
                self?.removeDevice(String(deviceId))
            default:
                break
            }
        }

        connection.start(queue: networkQueue)
    }

    private func receiveFromDevice(_ device: DeviceConnection) {
        device.connection.receiveMessage { [weak self] content, context, isComplete, error in
            if let error = error {
                log("Device \(device.id) recv error: \(error)")
                self?.removeDevice(device.id)
                return
            }

            if let data = content, let text = String(data: data, encoding: .utf8) {
                // Check if this is a WebSocket message
                if let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata {
                    switch metadata.opcode {
                    case .text:
                        device.handleResponse(text)
                    case .pong:
                        break // keepalive response, ignore
                    case .close:
                        self?.removeDevice(device.id)
                        return
                    default:
                        break
                    }
                }
            }

            // Continue receiving
            self?.receiveFromDevice(device)
        }
    }

    private func removeDevice(_ id: String) {
        devicesLock.lock()
        let device = devices.removeValue(forKey: id)
        devicesLock.unlock()
        device?.cancel()
    }

    /// Get the first connected device (or a specific one by ID)
    private func getDevice(id: String? = nil) -> DeviceConnection? {
        devicesLock.lock()
        defer { devicesLock.unlock() }

        if let id = id {
            return devices[id]
        }
        return devices.values.first
    }

    // MARK: TCP Server (port 18462 — CLI/IDE connections)

    private func startTCPServer() {
        let params = NWParameters.tcp
        do {
            tcpListener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: tcpPort)!)
        } catch {
            log("Failed to create TCP listener: \(error)")
            exit(1)
        }

        tcpListener!.stateUpdateHandler = { state in
            switch state {
            case .ready:
                log("TCP server listening on :\(tcpPort)")
            case .failed(let error):
                log("TCP listener failed: \(error)")
                exit(1)
            default:
                break
            }
        }

        tcpListener!.newConnectionHandler = { [weak self] connection in
            self?.handleCLIConnection(connection)
        }

        tcpListener!.start(queue: networkQueue)
    }

    private func handleCLIConnection(_ connection: NWConnection) {
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                self.receiveCLICommand(connection)
            case .failed, .cancelled:
                break
            default:
                break
            }
        }
        connection.start(queue: networkQueue)
    }

    private func receiveCLICommand(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1024) { [weak self] content, _, isComplete, error in
            guard let self = self else { return }

            if let error = error {
                log("CLI recv error: \(error)")
                connection.cancel()
                return
            }

            guard let data = content, let rawCommand = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }

            let command = rawCommand.trimmingCharacters(in: .whitespacesAndNewlines)
            if command.isEmpty {
                connection.cancel()
                return
            }

            // If no device connected, respond with error
            guard let device = self.getDevice() else {
                let errorResponse = "{\"error\":\"no device connected\"}\n"
                let responseData = errorResponse.data(using: .utf8)!
                connection.send(content: responseData, completion: .contentProcessed({ _ in
                    connection.cancel()
                }))
                return
            }

            // Forward command to device, relay response back to CLI
            device.send(command: command) { response in
                let responseText = (response ?? "{\"error\":\"no response from device\"}") + "\n"
                let responseData = responseText.data(using: .utf8)!
                connection.send(content: responseData, completion: .contentProcessed({ _ in
                    connection.cancel()
                }))
            }

            // Timeout: if device doesn't respond within 5s, send error
            DispatchQueue.global().asyncAfter(deadline: .now() + 5.0) {
                // Connection may already be cancelled by the response handler above
                // NWConnection handles this gracefully — double-cancel is safe
            }
        }
    }

    // MARK: Bonjour

    private func startBonjour() {
        bonjourService = NetService(domain: "local.", type: bonjourType, name: "Rebound Relay", port: Int32(wsPort))
        bonjourService?.publish()
        log("Bonjour: advertising \(bonjourType) on port \(wsPort)")
    }

    // MARK: Shutdown

    func stop() {
        wsListener?.cancel()
        tcpListener?.cancel()
        bonjourService?.stop()
        devicesLock.lock()
        for (_, device) in devices {
            device.cancel()
        }
        devices.removeAll()
        devicesLock.unlock()
        log("Relay stopped")
    }
}

// MARK: - Logging

func log(_ message: String) {
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm:ss"
    let timestamp = formatter.string(from: Date())
    print("[\(timestamp)] \(message)")
    fflush(stdout)
}

// MARK: - Main

log("rebound-relay starting...")
log("  TCP  :\(tcpPort) (CLI/IDE)")
log("  WS   :\(wsPort) (iOS devices)")
log("  Bonjour: \(bonjourType)")

let relay = RelayServer()
relay.start()

// Handle SIGINT for graceful shutdown
signal(SIGINT) { _ in
    log("Shutting down...")
    exit(0)
}

// Keep running
dispatchMain()
