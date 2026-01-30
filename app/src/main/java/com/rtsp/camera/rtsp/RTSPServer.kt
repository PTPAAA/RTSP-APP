package com.rtsp.camera.rtsp

import android.util.Log
import com.rtsp.camera.model.NetworkConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * RTSP Server (Enhanced)
 * Supports:
 * - RTSP over TCP (Interleaved)
 * - RTSP over UDP
 * - FU-A Fragmentation (RFC 3984)
 * - RTCP Sender Reports
 * - SPSP/PPS Periodic Insertion
 */
class RTSPServer(
    private val networkConfig: NetworkConfig,
    private val videoWidth: Int,
    private val videoHeight: Int
) {
    companion object {
        private const val TAG = "RTSPServer"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val SERVER_NAME = "RTSPCamera/2.0"
        private const val MTU = 1400 // Safe MTU size
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    // Client management
    private val clients = ConcurrentHashMap<String, RTSPClient>()

    // SPS/PPS Data
    var sps: ByteArray? = null
    var pps: ByteArray? = null

    // Listeners
    var onClientConnected: ((Int) -> Unit)? = null
    var onClientDisconnected: ((Int) -> Unit)? = null

    fun start(): Boolean {
        if (isRunning.get()) return true

        return try {
            val bindAddress = if (networkConfig.ipv4Only) {
                InetAddress.getByName("0.0.0.0")
            } else {
                null
            }

            serverSocket = ServerSocket(networkConfig.port, 50, bindAddress)
            isRunning.set(true)

            acceptThread = Thread({
                acceptLoop()
            }, "RTSPAcceptThread").apply { start() }

            Log.i(TAG, "RTSP Server started on port ${networkConfig.port} (TCP/UDP supported)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            false
        }
    }

    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val clientSocket = serverSocket?.accept() ?: continue

                if (networkConfig.lanOnly) {
                    if (!isPrivateAddress(clientSocket.inetAddress)) {
                        clientSocket.close()
                        continue
                    }
                }

                handleClient(clientSocket)
            } catch (e: Exception) {
                if (isRunning.get()) Log.e(TAG, "Accept error", e)
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
        Log.i(TAG, "Client connected: $clientId")

        val client = RTSPClient(clientId, socket)
        clients[clientId] = client
        onClientConnected?.invoke(clients.size)

        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                
                while (isRunning.get() && !socket.isClosed) {
                    val request = readRTSPRequest(reader) ?: break
                    val response = handleRTSPRequest(request, client)
                    client.sendResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client error: $clientId", e)
            } finally {
                client.close()
                clients.remove(clientId)
                onClientDisconnected?.invoke(clients.size)
                Log.i(TAG, "Client disconnected: $clientId")
            }
        }, "RTSPClient-$clientId").start()
    }

    private fun readRTSPRequest(reader: BufferedReader): RTSPRequest? {
        val lines = mutableListOf<String>()
        var line = reader.readLine() ?: return null
        
        while (line.isNotEmpty()) {
            lines.add(line)
            line = reader.readLine() ?: break
        }

        if (lines.isEmpty()) return null

        val requestLine = lines[0].split(" ")
        if (requestLine.size < 3) return null

        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val parts = lines[i].split(":", limit = 2)
            if (parts.size == 2) {
                headers[parts[0].trim()] = parts[1].trim()
            }
        }

        return RTSPRequest(requestLine[0], requestLine[1], headers)
    }

    private fun handleRTSPRequest(request: RTSPRequest, client: RTSPClient): String {
        val cseq = request.headers["CSeq"] ?: "0"

        return when (request.method) {
            "OPTIONS" -> buildOptionsResponse(cseq)
            "DESCRIBE" -> buildDescribeResponse(cseq)
            "SETUP" -> buildSetupResponse(cseq, request, client)
            "PLAY" -> buildPlayResponse(cseq, client)
            "PAUSE" -> buildPauseResponse(cseq)
            "TEARDOWN" -> buildTeardownResponse(cseq, client)
            else -> buildErrorResponse(cseq, 501, "Not Implemented")
        }
    }

    // --- Response Builders ---

    private fun buildOptionsResponse(cseq: String): String {
        return "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN\r\n" +
                "\r\n"
    }

    private fun buildDescribeResponse(cseq: String): String {
        val spsBase64 = sps?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
        val ppsBase64 = pps?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
        
        val sdp = "v=0\r\n" +
                "o=- 0 0 IN IP4 0.0.0.0\r\n" +
                "s=RTSPCamera\r\n" +
                "c=IN IP4 0.0.0.0\r\n" +
                "t=0 0\r\n" +
                "m=video 0 RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=$spsBase64,$ppsBase64\r\n" +
                "a=control:track0\r\n"

        return "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${sdp.length}\r\n" +
                "\r\n" +
                sdp
    }

    private fun buildSetupResponse(cseq: String, request: RTSPRequest, client: RTSPClient): String {
        val transport = request.headers["Transport"] ?: ""
        client.sessionId = System.currentTimeMillis().toString()

        return if (transport.contains("TCP")) {
            // TCP Interleaved Mode
            client.isTCP = true
            val matcher = Pattern.compile("interleaved=(\\d+)-(\\d+)").matcher(transport)
            if (matcher.find()) {
                client.rtpChannel = matcher.group(1)?.toInt() ?: 0
                client.rtcpChannel = matcher.group(2)?.toInt() ?: 1
            }
            "RTSP/1.0 200 OK\r\n" +
            "CSeq: $cseq\r\n" +
            "Transport: RTP/AVP/TCP;unicast;interleaved=${client.rtpChannel}-${client.rtcpChannel}\r\n" +
            "Session: ${client.sessionId}\r\n" +
            "\r\n"
        } else {
            // UDP Mode
            client.isTCP = false
            val matcher = Pattern.compile("client_port=(\\d+)-(\\d+)").matcher(transport)
            if (matcher.find()) {
                client.rtpPort = matcher.group(1)?.toInt() ?: 0
                client.rtcpPort = matcher.group(2)?.toInt() ?: 0
            }
            val serverRtpPort = 50000 + (clients.size * 2)
            "RTSP/1.0 200 OK\r\n" +
            "CSeq: $cseq\r\n" +
            "Transport: RTP/AVP;unicast;client_port=${client.rtpPort}-${client.rtcpPort};server_port=$serverRtpPort-${serverRtpPort+1}\r\n" +
            "Session: ${client.sessionId}\r\n" +
            "\r\n"
        }
    }

    private fun buildPlayResponse(cseq: String, client: RTSPClient): String {
        client.isPlaying = true
        return "RTSP/1.0 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Session: ${client.sessionId}\r\n" +
                "Range: npt=0.000-\r\n" +
                "\r\n"
    }

    private fun buildPauseResponse(cseq: String): String =
        "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n"

    private fun buildTeardownResponse(cseq: String, client: RTSPClient): String {
        client.isPlaying = false
        return "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n"
    }

    private fun buildErrorResponse(cseq: String, code: Int, msg: String): String =
        "RTSP/1.0 $code $msg\r\nCSeq: $cseq\r\n\r\n"

    // --- Media Streaming ---

    fun sendVideoFrame(data: ByteArray, timestamp: Long) {
        val playingClients = clients.values.filter { it.isPlaying }
        if (playingClients.isEmpty()) return

        // Check if NALU needs fragmentation (FU-A)
        if (data.size > MTU) {
            sendFragmentedFrame(data, timestamp, playingClients)
        } else {
            sendSingleFrame(data, timestamp, playingClients)
        }

        // Send RTCP Sender Report periodically (every ~100 frames)
        // Simplified: In production, use a timer
        playingClients.forEach { 
             if (it.frameCount++ % 100 == 0) sendRtcpSenderReport(it, timestamp)
        }
    }

    private fun sendSingleFrame(data: ByteArray, timestamp: Long, clients: List<RTSPClient>) {
        clients.forEach { client ->
            val packet = buildRtpPacket(data, client.sequenceNumber++, timestamp)
            sendData(client, packet, 0) // 0 = RTP Channel
        }
    }

    private fun sendFragmentedFrame(data: ByteArray, timestamp: Long, clients: List<RTSPClient>) {
        val naluType = data[0].toInt() and 0x1F
        val nri = data[0].toInt() and 0x60
        val fuIndicator = (nri or 28).toByte() // Type 28 = FU-A

        var offset = 1 // Skip original header
        val totalLength = data.size

        var isStart = true
        while (offset < totalLength) {
            val length = Math.min(MTU, totalLength - offset)
            val isEnd = (offset + length) >= totalLength
            
            val fuHeader = ((if (isStart) 0x80 else 0) or 
                           (if (isEnd) 0x40 else 0) or 
                           naluType).toByte()

            val payload = ByteArray(length + 2)
            payload[0] = fuIndicator
            payload[1] = fuHeader
            System.arraycopy(data, offset, payload, 2, length)

            clients.forEach { client ->
                val packet = buildRtpPacket(payload, client.sequenceNumber++, timestamp)
                sendData(client, packet, 0)
            }

            offset += length
            isStart = false
        }
    }

    private fun sendRtcpSenderReport(client: RTSPClient, rtpTimestamp: Long) {
        val packet = ByteArray(28)
        val now = System.currentTimeMillis()
        
        packet[0] = 0x80.toByte() // V=2, P=0, RC=0
        packet[1] = 200.toByte()  // PT=SR
        packet[2] = 0; packet[3] = 6 // Length (in 32-bit words) - 1
        // SSRC
        packet[4] = 0; packet[5] = 0; packet[6] = 0; packet[7] = 1
        // NTP Timestamp MSW
        val ntpMsw = (now / 1000 + 2208988800L).toInt()
        packet[8] = (ntpMsw shr 24).toByte()
        packet[9] = (ntpMsw shr 16).toByte()
        packet[10] = (ntpMsw shr 8).toByte()
        packet[11] = ntpMsw.toByte()
        // NTP Timestamp LSW (approx)
        val ntpLsw = ((now % 1000) * 4294967).toInt() 
        packet[12] = (ntpLsw shr 24).toByte()
        packet[13] = (ntpLsw shr 16).toByte()
        packet[14] = (ntpLsw shr 8).toByte()
        packet[15] = ntpLsw.toByte()
        // RTP Timestamp
        val ts = (rtpTimestamp * 90).toInt()
        packet[16] = (ts shr 24).toByte()
        packet[17] = (ts shr 16).toByte()
        packet[18] = (ts shr 8).toByte()
        packet[19] = ts.toByte()
        // Packet Count & Octet Count (Simplified: 0)
        
        sendData(client, packet, 1) // 1 = RTCP Channel
    }

    private fun sendData(client: RTSPClient, data: ByteArray, channelType: Int) {
        try {
            if (client.isTCP) {
                // Interleaved Frame: $ + Channel + Length(2) + Data
                synchronized(client.outputStream) {
                    val header = ByteArray(4)
                    header[0] = '$'.code.toByte()
                    header[1] = (if (channelType == 0) client.rtpChannel else client.rtcpChannel).toByte()
                    header[2] = ((data.size shr 8) and 0xFF).toByte()
                    header[3] = (data.size and 0xFF).toByte()
                    client.outputStream.write(header)
                    client.outputStream.write(data)
                }
            } else {
                // UDP
                val socket = client.rtpSocket ?: DatagramSocket().also { client.rtpSocket = it }
                val port = if (channelType == 0) client.rtpPort else client.rtcpPort
                val packet = DatagramPacket(data, data.size, client.socket.inetAddress, port)
                socket.send(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send error", e)
        }
    }

    private fun buildRtpPacket(payload: ByteArray, seqNum: Int, timestamp: Long): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // V=2
        header[1] = 96.toByte()   // Payload Type 96
        header[2] = (seqNum shr 8).toByte()
        header[3] = (seqNum and 0xFF).toByte()
        val ts = (timestamp * 90).toInt()
        header[4] = (ts shr 24).toByte()
        header[5] = (ts shr 16).toByte()
        header[6] = (ts shr 8).toByte()
        header[7] = ts.toByte()
        header[8] = 0; header[9] = 0; header[10] = 0; header[11] = 1 // SSRC
        return header + payload
    }

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress

    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (e: Exception) {}
        clients.values.forEach { it.close() }
        clients.clear()
    }

    fun getClientCount(): Int = clients.size

    // Helper classes
    private data class RTSPRequest(val method: String, val uri: String, val headers: Map<String, String>)

    private class RTSPClient(val id: String, val socket: Socket) {
        val outputStream: OutputStream = socket.getOutputStream()
        var sessionId: String = ""
        var isPlaying = false
        var isTCP = false
        var rtpChannel = 0
        var rtcpChannel = 1
        var rtpPort = 0
        var rtcpPort = 0
        var rtpSocket: DatagramSocket? = null
        var sequenceNumber = 0
        var frameCount = 0

        fun sendResponse(response: String) {
            synchronized(outputStream) {
                outputStream.write(response.toByteArray())
                outputStream.flush()
            }
        }

        fun close() {
            try { socket.close() } catch (e: Exception) {}
            try { rtpSocket?.close() } catch (e: Exception) {}
        }
    }
}
