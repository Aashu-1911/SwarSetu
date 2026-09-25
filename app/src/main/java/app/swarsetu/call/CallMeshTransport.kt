package app.swarsetu.call

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the high-throughput, low-latency socket transport over Wi-Fi Aware / Wi-Fi Direct
 * for bidirectional encrypted VoIP packets.
 */
class CallMeshTransport(
    private val scope: CoroutineScope,
    private val callId: String,
    private val localPeerId: String,
    private val remotePeerId: String,
    private val security: CallSecurity,
    private val jitterBuffer: JitterBuffer,
    private val onPacketReceived: (CallPacket) -> Unit,
    private val onConnectionClosed: (reason: String) -> Unit,
) {
    companion object {
        private const val TAG = "CallMeshTransport"
        const val DEFAULT_PORT = 42424
        private const val HEARTBEAT_INTERVAL_MS = 1000L
        private const val CONNECTION_TIMEOUT_MS = 6000L
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    private val outboundQueue = Channel<CallPacket>(Channel.UNLIMITED)
    private val sequenceCounter = AtomicLong(0)

    private var sendJob: Job? = null
    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _rttMs = MutableStateFlow<Long>(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private var lastReceivedPacketTimestamp = System.currentTimeMillis()

    /**
     * Starts listening as the Wi-Fi Aware socket server on [port].
     */
    suspend fun startServer(port: Int = DEFAULT_PORT): Int = withContext(Dispatchers.IO) {
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server
            val actualPort = server.localPort
            isRunning.set(true)

            scope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Wi-Fi Aware Call Server listening on port $actualPort...")
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    socket.soTimeout = 10000
                    clientSocket = socket
                    setupSocketStreams(socket)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Server accept error: ${e.message}", e)
                        onConnectionClosed("Server accept failed: ${e.message}")
                    }
                }
            }
            actualPort
        } catch (e: Exception) {
            Log.e(TAG, "Failed binding ServerSocket on $port: ${e.message}", e)
            -1
        }
    }

    /**
     * Connects to the remote peer's Wi-Fi Aware server at [host]:[port].
     */
    suspend fun connect(host: String, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to remote peer Wi-Fi Aware at $host:$port...")
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 8000)
            socket.soTimeout = 10000
            clientSocket = socket
            isRunning.set(true)
            setupSocketStreams(socket)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed connecting to $host:$port: ${e.message}", e)
            false
        }
    }

    private fun setupSocketStreams(socket: Socket) {
        try {
            outputStream = DataOutputStream(socket.getOutputStream())
            inputStream = DataInputStream(socket.getInputStream())
            isConnected.set(true)
            lastReceivedPacketTimestamp = System.currentTimeMillis()

            startSendLoop()
            startReceiveLoop()
            startHeartbeatWatchdog()

            Log.d(TAG, "CallMeshTransport connection established with $remotePeerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting up socket streams: ${e.message}", e)
            close("Stream setup failed")
        }
    }

    private fun startSendLoop() {
        sendJob?.cancel()
        sendJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected.get()) {
                val packet = outboundQueue.receive()
                try {
                    val encoded = CallPacketCodec.encode(packet)
                    val out = outputStream ?: break
                    out.writeInt(encoded.size)
                    out.write(encoded)
                    out.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Socket write error: ${e.message}")
                    close("Write error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun startReceiveLoop() {
        receiveJob?.cancel()
        receiveJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected.get()) {
                try {
                    val dis = inputStream ?: break
                    val frameLen = dis.readInt()
                    if (frameLen <= 0 || frameLen > 5 * 1024 * 1024) {
                        Log.w(TAG, "Invalid packet length received: $frameLen")
                        break
                    }
                    val frameBytes = ByteArray(frameLen)
                    dis.readFully(frameBytes)

                    val packet = CallPacketCodec.decode(frameBytes)
                    if (packet != null && packet.callId == callId) {
                        lastReceivedPacketTimestamp = System.currentTimeMillis()

                        if (packet.payloadType == CallPayloadType.HEARTBEAT) {
                            // Calculate RTT
                            val rtt = System.currentTimeMillis() - packet.timestampMs
                            _rttMs.value = rtt
                        } else {
                            jitterBuffer.push(packet)
                            onPacketReceived(packet)
                        }
                    }
                } catch (e: Exception) {
                    if (isConnected.get()) {
                        Log.e(TAG, "Socket read error: ${e.message}")
                        close("Read error: ${e.message}")
                    }
                    break
                }
            }
        }
    }

    private fun startHeartbeatWatchdog() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && isConnected.get()) {
                delay(HEARTBEAT_INTERVAL_MS)

                // 1. Send heartbeat ping
                val ping = createPacket(CallPayloadType.HEARTBEAT, ByteArray(0))
                sendPacket(ping)

                // 2. Check timeout
                val silenceDuration = System.currentTimeMillis() - lastReceivedPacketTimestamp
                if (silenceDuration > CONNECTION_TIMEOUT_MS) {
                    Log.w(TAG, "Call connection timed out (no packets for ${silenceDuration}ms)")
                    close("Connection timeout")
                    break
                }
            }
        }
    }

    /**
     * Encrypts and sends a payload to the remote peer.
     */
    fun sendPayload(type: CallPayloadType, rawPayload: ByteArray) {
        val packet = createPacket(type, rawPayload)
        sendPacket(packet)
    }

    private fun createPacket(type: CallPayloadType, rawPayload: ByteArray): CallPacket {
        val seq = sequenceCounter.getAndIncrement()
        val ts = System.currentTimeMillis()

        // Associated Authenticated Data
        val dummyPacket = CallPacket(callId, seq, ts, localPeerId, type, ByteArray(12), ByteArray(0))
        val aad = dummyPacket.computeAad()

        val (iv, encrypted) = security.encrypt(rawPayload, aad)
        return CallPacket(
            callId = callId,
            sequenceNumber = seq,
            timestampMs = ts,
            sourcePeerId = localPeerId,
            payloadType = type,
            iv = iv,
            encryptedPayload = encrypted,
        )
    }

    private fun sendPacket(packet: CallPacket) {
        if (isConnected.get()) {
            outboundQueue.trySend(packet)
        }
    }

    /**
     * Closes the socket transport.
     */
    fun close(reason: String = "Normal shutdown") {
        if (!isRunning.compareAndSet(true, false)) return
        isConnected.set(false)

        sendJob?.cancel()
        receiveJob?.cancel()
        heartbeatJob?.cancel()

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        outputStream = null
        inputStream = null
        clientSocket = null
        serverSocket = null

        Log.d(TAG, "CallMeshTransport closed: $reason")
        onConnectionClosed(reason)
    }
}
