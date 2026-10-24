package com.privatechat.app

import com.privatechat.app.crypto.SecureSession
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class NetworkPeer(private val onState: (String) -> Unit, private val onMessage: (String) -> Unit) {
    companion object { const val PORT = 45678; private const val MAX_MESSAGE_BYTES = 4096 }
    private val executor = Executors.newCachedThreadPool()
    private val generation = AtomicInteger(0)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var session: SecureSession? = null

    fun startHosting(roomCode: String) {
        stop()
        val run = generation.get()
        executor.execute {
            var listener: ServerSocket? = null
            try {
                val listening = ServerSocket(PORT)
                listening.reuseAddress = true
                listener = listening
                server = listening
                if (generation.get() != run) listening.close()
                onState("Waiting for the other phone on port $PORT")
                while (generation.get() == run && !listening.isClosed) {
                    var peerSocket: Socket? = null
                    try {
                        val accepted = listening.accept()
                        peerSocket = accepted
                        if (generation.get() != run) { accepted.close(); break }
                        accepted.tcpNoDelay = true
                        socket = accepted
                        onState("Authenticating peer...")
                        accepted.soTimeout = 15000
                        val input = DataInputStream(BufferedInputStream(accepted.getInputStream()))
                        val out = DataOutputStream(BufferedOutputStream(accepted.getOutputStream()))
                        val secure = SecureSession.handshake(input, out, roomCode, true)
                        accepted.soTimeout = 0
                        session = secure
                        output = out
                        onState("Connected securely")
                        receiveLoop(run, accepted, input, secure)
                        closeCurrent(accepted)
                        if (generation.get() == run) onState("Peer disconnected. Waiting to reconnect...")
                    } catch (error: Exception) {
                        if (peerSocket != null) closeCurrent(peerSocket)
                        if (generation.get() == run && !listening.isClosed) onState(friendlyError(error, "Connection ended. Waiting for peer..."))
                    }
                }
            } catch (error: Exception) {
                if (generation.get() == run) onState(friendlyError(error, "Could not start the host"))
            } finally {
                if (server === listener) server = null
            }
        }
    }

    fun connect(address: String, roomCode: String) {
        stop()
        val run = generation.get()
        executor.execute { clientRetryLoop(run, address.trim(), roomCode) }
    }

    private fun clientRetryLoop(run: Int, address: String, roomCode: String) {
        while (generation.get() == run) {
            var peerSocket: Socket? = null
            try {
                onState("Connecting directly to $address:$PORT...")
                val connected = Socket()
                peerSocket = connected
                socket = connected
                connected.tcpNoDelay = true
                connected.connect(InetSocketAddress(address, PORT), 8000)
                if (generation.get() != run) { connected.close(); break }
                connected.soTimeout = 15000
                val input = DataInputStream(BufferedInputStream(connected.getInputStream()))
                val out = DataOutputStream(BufferedOutputStream(connected.getOutputStream()))
                val secure = SecureSession.handshake(input, out, roomCode, false)
                connected.soTimeout = 0
                session = secure
                output = out
                onState("Connected securely")
                receiveLoop(run, connected, input, secure)
                closeCurrent(connected)
                if (generation.get() == run) onState("Disconnected. Reconnecting...")
            } catch (error: Exception) {
                if (peerSocket != null) closeCurrent(peerSocket)
                if (generation.get() == run) onState(friendlyError(error, "Could not connect. Retrying..."))
            }
            if (generation.get() == run) try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
        }
    }

    @Synchronized fun sendMessage(text: String): Boolean {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return false
        return try {
            val currentSession = session ?: return false
            val out = output ?: return false
            val encrypted = currentSession.encrypt(bytes)
            synchronized(out) { out.writeInt(encrypted.size); out.write(encrypted); out.flush() }
            true
        } catch (error: Exception) {
            onState(friendlyError(error, "Message could not be sent"))
            closeCurrent()
            false
        }
    }

    private fun receiveLoop(run: Int, peerSocket: Socket, input: DataInputStream, secure: SecureSession) {
        while (generation.get() == run && !peerSocket.isClosed) {
            val size = input.readInt()
            require(size in 16..(MAX_MESSAGE_BYTES + 16)) { "Invalid message frame" }
            val plain = secure.decrypt(ByteArray(size).also(input::readFully))
            val text = String(plain, StandardCharsets.UTF_8)
            require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Message too long" }
            onMessage(text)
        }
    }

    fun stop() {
        generation.incrementAndGet()
        try { server?.close() } catch (_: Exception) {}
        closeCurrent()
    }

    @Synchronized private fun closeCurrent(expected: Socket? = null) {
        if (expected != null && socket !== expected) return
        session = null
        output = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private fun friendlyError(error: Exception, fallback: String): String = when (error) {
        is java.net.ConnectException -> "Connection refused. Check the host IP, Wi-Fi and port."
        is java.net.SocketTimeoutException -> "Connection timed out. Check both phones are reachable."
        is java.io.EOFException -> "Peer disconnected."
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}