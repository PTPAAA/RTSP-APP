package com.rtsp.camera.rtsp

import android.util.Log
import com.rtsp.camera.model.NetworkConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 多码流RTSP服务器
 * 支持主码流（高清）和子码流（流畅）同时推送
 */
class MultiStreamRTSPServer(
    private val networkConfig: NetworkConfig
) {
    companion object {
        private const val TAG = "MultiStreamRTSPServer"
        private const val RTSP_VERSION = "RTSP/1.0"
        private const val SERVER_NAME = "RTSPCamera/1.0"
    }
    
    /**
     * 码流配置
     */
    data class StreamConfig(
        val path: String,           // URL路径，如 "main" 或 "sub"
        val width: Int,
        val height: Int,
        val bitrate: Int,           // kbps
        val frameRate: Int,
        // 音频配置
        val enableAudio: Boolean = false,
        val audioSampleRate: Int = 44100,
        val audioChannels: Int = 1
    )
    
    /**
     * 码流实例
     */
    inner class StreamInstance(val config: StreamConfig) {
        // 视频参数
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var videoSequenceNumber = 0
        
        // 音频参数
        var audioSpecificConfig: ByteArray? = null
        var audioSequenceNumber = 0
        
        val clients = ConcurrentHashMap<String, RTSPClient>()
        var sequenceNumber = 0
        var lastRtcpTime = 0L
    }
    
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    
    // 多码流实例
    private val streams = ConcurrentHashMap<String, StreamInstance>()
    
    // 监听器
    var onClientConnected: ((String, Int) -> Unit)? = null  // (streamPath, totalCount)
    var onClientDisconnected: ((String, Int) -> Unit)? = null
    var onClientStartedPlaying: ((String) -> Unit)? = null  // 客户端开始播放时请求关键帧
    
    // 带宽统计
    private var bytesSentLastSecond = 0L
    private var bytesReceivedLastSecond = 0L
    private var lastBandwidthResetTime = System.currentTimeMillis()
    @Volatile var currentUploadBandwidth = 0L    // bytes per second
        private set
    @Volatile var currentDownloadBandwidth = 0L  // bytes per second (RTCP 等)
        private set
    
    /**
     * 注册码流
     */
    fun registerStream(config: StreamConfig): StreamInstance {
        val instance = StreamInstance(config)
        streams[config.path] = instance
        Log.i(TAG, "Registered stream: ${config.path} (${config.width}x${config.height})")
        return instance
    }
    
    /**
     * 获取码流实例
     */
    fun getStream(path: String): StreamInstance? = streams[path]
    
    /**
     * 设置SPS/PPS
     */
    fun setSpsPps(streamPath: String, sps: ByteArray?, pps: ByteArray?) {
        streams[streamPath]?.apply {
            this.sps = sps
            this.pps = pps
        }
    }
    
    /**
     * 设置音频配置 (Audio Specific Config)
     */
    fun setAudioConfig(streamPath: String, audioSpecificConfig: ByteArray?) {
        streams[streamPath]?.apply {
            this.audioSpecificConfig = audioSpecificConfig
        }
    }
    
    /**
     * 启动服务器
     */
    fun start(): Boolean {
        if (isRunning.get()) return true
        
        return try {
            val bindAddress = if (networkConfig.ipv4Only) {
                InetAddress.getByName("0.0.0.0")
            } else null
            
            serverSocket = ServerSocket(networkConfig.port, 50, bindAddress)
            isRunning.set(true)
            
            acceptThread = Thread({
                acceptLoop()
            }, "MultiStreamRTSPAccept").apply { start() }
            
            Log.i(TAG, "Multi-stream RTSP Server started on port ${networkConfig.port}")
            Log.i(TAG, "Available streams: ${streams.keys}")
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
                
                // 网络过滤
                if (networkConfig.lanOnly && !isPrivateAddress(clientSocket.inetAddress)) {
                    clientSocket.close()
                    continue
                }
                
                if (networkConfig.ipv4Only && clientSocket.inetAddress !is Inet4Address) {
                    clientSocket.close()
                    continue
                }
                
                handleClient(clientSocket)
            } catch (e: SocketException) {
                if (isRunning.get()) Log.e(TAG, "Socket error", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting client", e)
            }
        }
    }
    
    private fun handleClient(socket: Socket) {
        val clientId = "${socket.inetAddress.hostAddress}:${socket.port}"
        Log.d(TAG, "New client connected: $clientId")
        
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = PrintWriter(socket.getOutputStream(), true)
                
                var currentStream: StreamInstance? = null
                val client = RTSPClient(
                    id = clientId,
                    socket = socket,
                    writer = writer
                )
                
                while (isRunning.get() && !socket.isClosed) {
                    val request = readRTSPRequest(reader) ?: break
                    Log.d(TAG, "RTSP Request from $clientId: ${request.method} ${request.uri}")
                    
                    // 从URI解析目标码流
                    val streamPath = extractStreamPath(request.uri)
                    Log.d(TAG, "Extracted stream path: $streamPath (from ${request.uri})")
                    
                    if (streamPath != null && currentStream == null) {
                        currentStream = streams[streamPath]
                        if (currentStream != null) {
                            client.streamPath = streamPath  // 记录客户端订阅的流路径
                            currentStream.clients[clientId] = client
                            Log.i(TAG, "Client $clientId registered to stream: $streamPath")
                            onClientConnected?.invoke(streamPath, getTotalClientCount())
                        }
                    }
                    
                    val response = handleRTSPRequest(request, client, currentStream)
                    Log.d(TAG, "RTSP Response: ${response.lines().firstOrNull()}")
                    writer.print(response)
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client $clientId", e)
            } finally {
                Log.d(TAG, "Client disconnected: $clientId")
                // 从所有码流中移除客户端
                streams.values.forEach { stream ->
                    if (stream.clients.remove(clientId) != null) {
                        onClientDisconnected?.invoke(stream.config.path, getTotalClientCount())
                    }
                }
                try { socket.close() } catch (_: Exception) {}
            }
        }, "RTSPClient-$clientId").start()
    }
    
    /**
     * 从URI提取码流路径
     * 支持格式: 
     * - rtsp://ip:port/main
     * - rtsp://ip:port/main/track0
     * - rtsp://ip:port/sub/track0  
     */
    private fun extractStreamPath(uri: String): String? {
        // 移除 track0/track1 等后缀
        val cleanUri = uri.replace(Regex("/track\\d+$"), "")
        
        // 提取路径部分
        val parts = cleanUri.split("/")
        val path = parts.lastOrNull { it.isNotEmpty() && !it.contains(":") && !it.contains("?") }
        
        Log.d(TAG, "extractStreamPath: uri=$uri, cleanUri=$cleanUri, path=$path, streams=${streams.keys}")
        
        return if (streams.containsKey(path)) path else null
    }
    
    private fun readRTSPRequest(reader: BufferedReader): RTSPRequest? {
        val lines = mutableListOf<String>()
        var line: String?
        
        while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrEmpty()) break
            lines.add(line!!)
        }
        
        if (lines.isEmpty()) return null
        
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 3) return null
        
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val colonIndex = lines[i].indexOf(':')
            if (colonIndex > 0) {
                headers[lines[i].substring(0, colonIndex).trim()] = 
                    lines[i].substring(colonIndex + 1).trim()
            }
        }
        
        return RTSPRequest(requestLine[0], requestLine[1], headers)
    }
    
    private fun handleRTSPRequest(
        request: RTSPRequest, 
        client: RTSPClient, 
        stream: StreamInstance?
    ): String {
        val cseq = request.headers["CSeq"] ?: "0"
        
        // OPTIONS 不需要鉴权
        if (request.method == "OPTIONS") {
            return buildOptionsResponse(cseq)
        }
        
        // 检查鉴权（如果启用）
        if (networkConfig.enableAuth && networkConfig.username.isNotEmpty()) {
            val authResult = checkAuthorization(request)
            if (!authResult) {
                return buildUnauthorizedResponse(cseq, request.uri)
            }
        }
        
        return when (request.method) {
            "DESCRIBE" -> buildDescribeResponse(cseq, request.uri, stream)
            "SETUP" -> buildSetupResponse(cseq, request, client)
            "PLAY" -> buildPlayResponse(cseq, client)
            "PAUSE" -> buildPauseResponse(cseq)
            "TEARDOWN" -> buildTeardownResponse(cseq, client)
            else -> buildErrorResponse(cseq, 501, "Not Implemented")
        }
    }
    
    /**
     * 检查 Digest 鉴权
     */
    private fun checkAuthorization(request: RTSPRequest): Boolean {
        val authHeader = request.headers["Authorization"] ?: return false
        
        if (!authHeader.startsWith("Digest ")) {
            // 也支持 Basic 鉴权
            if (authHeader.startsWith("Basic ")) {
                return checkBasicAuth(authHeader)
            }
            return false
        }
        
        return checkDigestAuth(authHeader, request.method, request.uri)
    }
    
    /**
     * Basic 鉴权检查
     */
    private fun checkBasicAuth(authHeader: String): Boolean {
        try {
            val encoded = authHeader.substring(6).trim()
            val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
            val parts = decoded.split(":", limit = 2)
            if (parts.size == 2) {
                return parts[0] == networkConfig.username && parts[1] == networkConfig.password
            }
        } catch (e: Exception) {
            Log.e(TAG, "Basic auth decode error", e)
        }
        return false
    }
    
    /**
     * Digest 鉴权检查
     */
    private fun checkDigestAuth(authHeader: String, method: String, uri: String): Boolean {
        try {
            // 解析 Digest 参数
            val params = parseDigestParams(authHeader.substring(7))
            
            val username = params["username"] ?: return false
            val realm = params["realm"] ?: return false
            val nonce = params["nonce"] ?: return false
            val responseHash = params["response"] ?: return false
            val digestUri = params["uri"] ?: uri
            
            if (username != networkConfig.username) return false
            
            // 计算期望的 response
            val ha1 = md5("${networkConfig.username}:$realm:${networkConfig.password}")
            val ha2 = md5("$method:$digestUri")
            val expectedResponse = md5("$ha1:$nonce:$ha2")
            
            return responseHash == expectedResponse
        } catch (e: Exception) {
            Log.e(TAG, "Digest auth error", e)
            return false
        }
    }
    
    /**
     * 解析 Digest 参数
     */
    private fun parseDigestParams(params: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val pattern = Pattern.compile("""(\w+)=(?:"([^"]+)"|([^\s,]+))""")
        val matcher = pattern.matcher(params)
        while (matcher.find()) {
            val key = matcher.group(1) ?: continue
            val value = matcher.group(2) ?: matcher.group(3) ?: continue
            result[key] = value
        }
        return result
    }
    
    /**
     * 计算 MD5
     */
    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    // 用于 Digest 鉴权的 nonce（每次服务器启动生成一个）
    private val authNonce = System.currentTimeMillis().toString(16)
    private val authRealm = "RTSPCamera"
    
    /**
     * 构建 401 Unauthorized 响应
     */
    private fun buildUnauthorizedResponse(cseq: String, uri: String): String {
        return "$RTSP_VERSION 401 Unauthorized\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n" +
                "WWW-Authenticate: Digest realm=\"$authRealm\", nonce=\"$authNonce\"\r\n" +
                "WWW-Authenticate: Basic realm=\"$authRealm\"\r\n\r\n"
    }
    
    private fun buildOptionsResponse(cseq: String): String {
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n" +
                "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN\r\n\r\n"
    }
    
    private fun buildDescribeResponse(cseq: String, uri: String, stream: StreamInstance?): String {
        val config = stream?.config
        val spsBase64 = stream?.sps?.let { 
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) 
        } ?: ""
        val ppsBase64 = stream?.pps?.let { 
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) 
        } ?: ""
        
        val streamInfo = if (config != null) {
            "${config.width}x${config.height} @ ${config.bitrate}kbps"
        } else "Unknown"
        
        // 基础视频 SDP
        val sdpBuilder = StringBuilder()
        sdpBuilder.append("v=0\r\n")
        sdpBuilder.append("o=- 0 0 IN IP4 0.0.0.0\r\n")
        sdpBuilder.append("s=RTSPCamera Stream ($streamInfo)\r\n")
        sdpBuilder.append("c=IN IP4 0.0.0.0\r\n")
        sdpBuilder.append("t=0 0\r\n")
        
        // 视频轨道 (track0)
        sdpBuilder.append("m=video 0 RTP/AVP 96\r\n")
        sdpBuilder.append("a=rtpmap:96 H264/90000\r\n")
        sdpBuilder.append("a=fmtp:96 packetization-mode=1;profile-level-id=42001f;sprop-parameter-sets=$spsBase64,$ppsBase64\r\n")
        sdpBuilder.append("a=control:track0\r\n")
        
        // 音频轨道 (track1) - 仅当启用音频时
        if (config?.enableAudio == true) {
            val ascBase64 = stream.audioSpecificConfig?.let {
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            } ?: ""
            val sampleRate = config.audioSampleRate
            val channels = config.audioChannels
            
            sdpBuilder.append("m=audio 0 RTP/AVP 97\r\n")
            sdpBuilder.append("a=rtpmap:97 MPEG4-GENERIC/$sampleRate/$channels\r\n")
            sdpBuilder.append("a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$ascBase64\r\n")
            sdpBuilder.append("a=control:track1\r\n")
        }
        
        val sdp = sdpBuilder.toString()
        
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n" +
                "Content-Type: application/sdp\r\n" +
                "Content-Length: ${sdp.length}\r\n\r\n" +
                sdp
    }
    
    private fun buildSetupResponse(cseq: String, request: RTSPRequest, client: RTSPClient): String {
        val transport = request.headers["Transport"] ?: ""
        Log.d(TAG, "SETUP Transport header: $transport")
        
        if (client.sessionId.isEmpty()) {
            client.sessionId = System.currentTimeMillis().toString()
        }
        
        // 判断是视频 track (track0) 还是音频 track (track1)
        val isAudioTrack = request.uri.contains("track1")
        
        // 检测是否是 TCP interleaved 模式
        if (transport.contains("TCP") || transport.contains("interleaved")) {
            // TCP interleaved 模式
            client.isTcpMode = true
            val interleavedPattern = Pattern.compile("interleaved=(\\d+)-(\\d+)")
            val interleavedMatcher = interleavedPattern.matcher(transport)
            if (interleavedMatcher.find()) {
                val rtpChannel = interleavedMatcher.group(1)?.toIntOrNull() ?: 0
                val rtcpChannel = interleavedMatcher.group(2)?.toIntOrNull() ?: 1
                
                if (isAudioTrack) {
                    // 音频 track1
                    client.audioInterleavedRtpChannel = rtpChannel
                    client.audioInterleavedRtcpChannel = rtcpChannel
                    Log.i(TAG, "Client using TCP interleaved mode for AUDIO: RTP channel=$rtpChannel, RTCP channel=$rtcpChannel")
                } else {
                    // 视频 track0
                    client.interleavedRtpChannel = rtpChannel
                    client.interleavedRtcpChannel = rtcpChannel
                    Log.i(TAG, "Client using TCP interleaved mode for VIDEO: RTP channel=$rtpChannel, RTCP channel=$rtcpChannel")
                }
            }
            
            val rtpChannel = if (isAudioTrack) client.audioInterleavedRtpChannel else client.interleavedRtpChannel
            val rtcpChannel = if (isAudioTrack) client.audioInterleavedRtcpChannel else client.interleavedRtcpChannel
            val responseTransport = "RTP/AVP/TCP;unicast;interleaved=$rtpChannel-$rtcpChannel"
            Log.d(TAG, "SETUP Response Transport: $responseTransport")
            
            return "$RTSP_VERSION 200 OK\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Server: $SERVER_NAME\r\n" +
                    "Transport: $responseTransport\r\n" +
                    "Session: ${client.sessionId};timeout=60\r\n\r\n"
        } else {
            // UDP 模式
            client.isTcpMode = false
            val portPattern = Pattern.compile("client_port=(\\d+)-(\\d+)")
            val matcher = portPattern.matcher(transport)
            
            if (matcher.find()) {
                client.rtpPort = matcher.group(1)?.toIntOrNull() ?: 0
                client.rtcpPort = matcher.group(2)?.toIntOrNull() ?: 0
                Log.d(TAG, "Client ports: RTP=${client.rtpPort}, RTCP=${client.rtcpPort}")
            }
            
            val serverRtpPort = 50000 + (System.nanoTime() % 1000).toInt() * 2
            val responseTransport = "RTP/AVP;unicast;client_port=${client.rtpPort}-${client.rtcpPort};server_port=$serverRtpPort-${serverRtpPort + 1}"
            Log.d(TAG, "SETUP Response Transport: $responseTransport")
            
            return "$RTSP_VERSION 200 OK\r\n" +
                    "CSeq: $cseq\r\n" +
                    "Server: $SERVER_NAME\r\n" +
                    "Transport: $responseTransport\r\n" +
                    "Session: ${client.sessionId};timeout=60\r\n\r\n"
        }
    }
    
    private fun buildPlayResponse(cseq: String, client: RTSPClient): String {
        client.isPlaying = true
        
        // 通知请求关键帧（确保新客户端能收到 IDR 帧）
        client.streamPath?.let { path ->
            onClientStartedPlaying?.invoke(path)
        }
        
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n" +
                "Session: ${client.sessionId}\r\n" +
                "Range: npt=0.000-\r\n\r\n"
    }
    
    private fun buildPauseResponse(cseq: String): String {
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n\r\n"
    }
    
    private fun buildTeardownResponse(cseq: String, client: RTSPClient): String {
        client.isPlaying = false
        return "$RTSP_VERSION 200 OK\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n\r\n"
    }
    
    private fun buildErrorResponse(cseq: String, code: Int, message: String): String {
        return "$RTSP_VERSION $code $message\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: $SERVER_NAME\r\n\r\n"
    }
    
    /**
     * 发送视频帧到指定码流的所有客户端
     */
    private var frameSentCount = 0
    fun sendVideoFrame(streamPath: String, data: ByteArray, timestamp: Long) {
        val stream = streams[streamPath] ?: return
        val playingClients = stream.clients.values.filter { it.isPlaying }
        
        if (playingClients.isEmpty()) return
        
        // 解析 NAL 单元（去除 start code）
        val nalUnits = parseNalUnits(data)
        
        frameSentCount++
        if (frameSentCount <= 3) {
            Log.d(TAG, "sendVideoFrame[$streamPath]: dataSize=${data.size}, nalUnits=${nalUnits.size}, clients=${playingClients.size}")
        }
        
        // 检查 IDR 帧 (Type 5) 并插入 SPS/PPS
        val isKeyFrame = nalUnits.any { (it[0].toInt() and 0x1F) == 5 }
        if (isKeyFrame) {
            stream.sps?.let { nal ->
                val stripped = stripStartCode(nal)
                playingClients.forEach { client -> 
                   try { sendNalUnit(client, stripped, timestamp, stream, false) } catch (e: Exception) {}
                }
            }
            stream.pps?.let { nal ->
                val stripped = stripStartCode(nal)
                playingClients.forEach { client -> 
                   try { sendNalUnit(client, stripped, timestamp, stream, false) } catch (e: Exception) {}
                }
            }
        }

        // 发送数据帧
        for (client in playingClients) {
            for ((index, nalUnit) in nalUnits.withIndex()) {
                val isLastNal = index == nalUnits.size - 1
                try {
                    sendNalUnit(client, nalUnit, timestamp, stream, isLastNal)
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending to client ${client.id}", e)
                }
            }
        }

        // 定期发送 RTCP Sender Report (每 3 秒)
        val now = System.currentTimeMillis()
        if (now - stream.lastRtcpTime > 3000) {
            stream.lastRtcpTime = now
            val rtcpPacket = buildSenderReport(now, timestamp)
            playingClients.forEach { 
                sendRtcpPacket(it, rtcpPacket) 
            }
        }
    }
    
    /**
     * 发送音频帧到指定码流的所有客户端
     * AAC 帧封装为 RTP (RFC 3640 AAC-hbr 模式)
     */
    fun sendAudioFrame(streamPath: String, data: ByteArray, timestamp: Long) {
        val stream = streams[streamPath] ?: return
        if (!stream.config.enableAudio) return
        
        val playingClients = stream.clients.values.filter { it.isPlaying }
        if (playingClients.isEmpty()) return
        
        // RFC 3640 AAC-hbr 模式 RTP payload 格式:
        // 1. AU-headers-length (2 bytes): AU headers 总位数
        // 2. AU-header (2 bytes per AU): AU-size (13 bits) + AU-index (3 bits)
        // 3. AU data
        
        val auSize = data.size
        
        // AU-headers-length = 16 bits (一个 AU header 的位数)
        val auHeadersLength = 16
        
        // AU-header: 13 bits size + 3 bits index (index=0 for first AU)
        val auHeader = ByteArray(2)
        auHeader[0] = ((auSize shr 5) and 0xFF).toByte()
        auHeader[1] = ((auSize and 0x1F) shl 3).toByte()
        
        // 完整 payload: AU-headers-length (2 bytes) + AU-header (2 bytes) + AU data
        val payload = ByteArray(4 + data.size)
        // AU-headers-length (big-endian)
        payload[0] = ((auHeadersLength shr 8) and 0xFF).toByte()
        payload[1] = (auHeadersLength and 0xFF).toByte()
        // AU-header
        payload[2] = auHeader[0]
        payload[3] = auHeader[1]
        // AU data
        System.arraycopy(data, 0, payload, 4, data.size)
        
        // 音频时间戳基于采样率 (例如 44100Hz)
        val sampleRate = stream.config.audioSampleRate
        val rtpTimestamp = (timestamp * sampleRate / 1000).toInt()
        
        for (client in playingClients) {
            try {
                val rtpPacket = buildAudioRtpPacket(payload, stream.audioSequenceNumber++, rtpTimestamp)
                sendRtpPacket(client, rtpPacket, isAudio = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending audio to client ${client.id}", e)
            }
        }
    }
    
    /**
     * 构建音频 RTP 包
     */
    private fun buildAudioRtpPacket(payload: ByteArray, seqNum: Int, timestamp: Int): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // Version 2
        header[1] = (97 or 0x80).toByte() // PT=97 (音频), M=1 (每帧都是完整的)
        header[2] = (seqNum shr 8).toByte()
        header[3] = (seqNum and 0xFF).toByte()
        header[4] = (timestamp shr 24).toByte()
        header[5] = (timestamp shr 16).toByte()
        header[6] = (timestamp shr 8).toByte()
        header[7] = timestamp.toByte()
        // SSRC (音频使用不同的 SSRC)
        header[8] = 0x87.toByte()
        header[9] = 0x65.toByte()
        header[10] = 0x43.toByte()
        header[11] = 0x21.toByte()
        return header + payload
    }

    private fun buildSenderReport(ntpTime: Long, rtpTimestamp: Long): ByteArray {
        val packet = ByteArray(28)
        // V=2, P=0, RC=0 -> 1000 0000 = 0x80
        packet[0] = 0x80.toByte()
        // PT=SR=200
        packet[1] = 200.toByte()
        // Length = 6 (28 bytes / 4 - 1)
        packet[2] = 0; packet[3] = 6
        // SSRC
        packet[4] = 0x12.toByte(); packet[5] = 0x34.toByte(); packet[6] = 0x56.toByte(); packet[7] = 0x78.toByte()
        
        // NTP Timestamp
        val ntpMsw = (ntpTime / 1000 + 2208988800L).toInt()
        val ntpLsw = ((ntpTime % 1000) * 4294967).toInt()
        packet[8] = (ntpMsw shr 24).toByte(); packet[9] = (ntpMsw shr 16).toByte(); packet[10] = (ntpMsw shr 8).toByte(); packet[11] = ntpMsw.toByte()
        packet[12] = (ntpLsw shr 24).toByte(); packet[13] = (ntpLsw shr 16).toByte(); packet[14] = (ntpLsw shr 8).toByte(); packet[15] = ntpLsw.toByte()
        
        // RTP Timestamp
        val ts = (rtpTimestamp * 90).toInt()
        packet[16] = (ts shr 24).toByte(); packet[17] = (ts shr 16).toByte(); packet[18] = (ts shr 8).toByte(); packet[19] = ts.toByte()
        
        // Sender's Packet Count (0) & Octet Count (0) - Simplified
        // packet[20]..packet[27] default 0
        
        return packet
    }

    private fun sendRtcpPacket(client: RTSPClient, data: ByteArray) {
        try {
            if (client.isTcpMode) {
                synchronized(client.socket) {
                    val outputStream = client.socket.getOutputStream()
                    outputStream.write('$'.code)
                    outputStream.write(client.interleavedRtcpChannel)
                    outputStream.write((data.size shr 8) and 0xFF)
                    outputStream.write(data.size and 0xFF)
                    outputStream.write(data)
                    outputStream.flush()
                }
            } else {
                val socket = client.rtpSocket ?: DatagramSocket().also { client.rtpSocket = it }
                val packet = DatagramPacket(data, data.size, client.socket.inetAddress, client.rtcpPort)
                socket.send(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RTCP send error", e)
        }
    }
    
    /**
     * 解析 NAL 单元，去除 start code (0x00 0x00 0x00 0x01 或 0x00 0x00 0x01)
     */
    private fun parseNalUnits(data: ByteArray): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1
        
        while (i < data.size - 3) {
            // 检查 4 字节 start code
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && 
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                if (nalStart >= 0) {
                    nalUnits.add(data.copyOfRange(nalStart, i))
                }
                i += 4
                nalStart = i
            }
            // 检查 3 字节 start code
            else if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (nalStart >= 0) {
                    nalUnits.add(data.copyOfRange(nalStart, i))
                }
                i += 3
                nalStart = i
            } else {
                i++
            }
        }
        
        // 添加最后一个 NAL 单元
        if (nalStart >= 0 && nalStart < data.size) {
            nalUnits.add(data.copyOfRange(nalStart, data.size))
        }
        
        // 如果没有找到任何 start code，尝试剥离开头的 start code 并返回整个数据
        if (nalUnits.isEmpty() && data.isNotEmpty()) {
            val stripped = stripStartCode(data)
            if (stripped.isNotEmpty()) {
                nalUnits.add(stripped)
            }
        }
        
        return nalUnits
    }
    
    /**
     * 剥离 NAL 单元开头的 start code
     */
    private fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && 
            data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }
    
    /**
     * 发送单个 NAL 单元（支持 FU-A 分片，支持 TCP/UDP）
     */
    private fun sendNalUnit(client: RTSPClient, nalUnit: ByteArray, timestamp: Long, stream: StreamInstance, isLastNal: Boolean) {
        if (nalUnit.isEmpty()) return
        
        val maxPayloadSize = 1400 // MTU - RTP header
        
        if (nalUnit.size <= maxPayloadSize) {
            // 单包模式
            val rtpPacket = buildRtpPacket(nalUnit, stream.sequenceNumber++, timestamp, isLastNal)
            sendRtpPacket(client, rtpPacket)
        } else {
            // FU-A 分片模式
            val nalHeader = nalUnit[0]
            val nalType = nalHeader.toInt() and 0x1F
            val nri = nalHeader.toInt() and 0x60
            
            var offset = 1
            var isFirst = true
            
            while (offset < nalUnit.size) {
                val remaining = nalUnit.size - offset
                val fragmentSize = minOf(remaining, maxPayloadSize - 2) // -2 for FU indicator and FU header
                val isLast = (offset + fragmentSize >= nalUnit.size)
                
                // FU indicator: same NRI, type = 28 (FU-A)
                val fuIndicator = ((nri or 28).toByte())
                
                // FU header: S/E bits + original NAL type
                var fuHeader = nalType.toByte()
                if (isFirst) {
                    fuHeader = (fuHeader.toInt() or 0x80).toByte() // Start bit
                    isFirst = false
                }
                if (isLast) {
                    fuHeader = (fuHeader.toInt() or 0x40).toByte() // End bit
                }
                
                val fragment = ByteArray(2 + fragmentSize)
                fragment[0] = fuIndicator
                fragment[1] = fuHeader
                System.arraycopy(nalUnit, offset, fragment, 2, fragmentSize)
                
                val marker = isLast && isLastNal
                val rtpPacket = buildRtpPacket(fragment, stream.sequenceNumber++, timestamp, marker)
                sendRtpPacket(client, rtpPacket)
                
                offset += fragmentSize
            }
        }
    }
    
    /**
     * 发送 RTP 数据包（根据客户端模式选择 TCP 或 UDP）
     * @param isAudio 是否是音频包（用于选择正确的 interleaved channel）
     */
    private fun sendRtpPacket(client: RTSPClient, rtpPacket: ByteArray, isAudio: Boolean = false) {
        try {
            // 带宽统计
            val packetSize = rtpPacket.size
            bytesSentLastSecond += packetSize
            
            // 每秒更新一次带宽
            val now = System.currentTimeMillis()
            if (now - lastBandwidthResetTime >= 1000) {
                currentUploadBandwidth = bytesSentLastSecond
                currentDownloadBandwidth = bytesReceivedLastSecond
                bytesSentLastSecond = 0
                bytesReceivedLastSecond = 0
                lastBandwidthResetTime = now
            }
            
            if (client.isTcpMode) {
                // TCP interleaved 模式: $channel[2字节长度][RTP数据]
                synchronized(client.socket) {
                    val outputStream = client.socket.getOutputStream()
                    // 根据音频/视频选择正确的 channel
                    val channel = if (isAudio) client.audioInterleavedRtpChannel else client.interleavedRtpChannel
                    val length = rtpPacket.size
                    
                    // 写入 interleaved header: $ + channel + 2字节大端长度
                    outputStream.write('$'.code)
                    outputStream.write(channel)
                    outputStream.write((length shr 8) and 0xFF)
                    outputStream.write(length and 0xFF)
                    outputStream.write(rtpPacket)
                    outputStream.flush()
                }
            } else {
                // UDP 模式
                val socket = client.rtpSocket ?: run {
                    val newSocket = DatagramSocket()
                    client.rtpSocket = newSocket
                    newSocket
                }
                val packet = DatagramPacket(
                    rtpPacket, rtpPacket.size, 
                    client.socket.inetAddress, client.rtpPort
                )
                socket.send(packet)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send RTP packet to ${client.id}: ${e.message}")
        }
    }
    
    private fun buildRtpPacket(payload: ByteArray, seqNum: Int, timestamp: Long, marker: Boolean = false): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte() // Version 2
        header[1] = if (marker) (96 or 0x80).toByte() else 96.toByte() // PT=96, M bit
        header[2] = (seqNum shr 8).toByte()
        header[3] = (seqNum and 0xFF).toByte()
        val ts = (timestamp * 90).toInt()
        header[4] = (ts shr 24).toByte()
        header[5] = (ts shr 16).toByte()
        header[6] = (ts shr 8).toByte()
        header[7] = ts.toByte()
        // SSRC
        header[8] = 0x12.toByte()
        header[9] = 0x34.toByte()
        header[10] = 0x56.toByte()
        header[11] = 0x78.toByte()
        return header + payload
    }
    
    private fun isPrivateAddress(address: InetAddress): Boolean {
        return address.isSiteLocalAddress || address.isLinkLocalAddress
    }
    
    fun getTotalClientCount(): Int = streams.values.sumOf { it.clients.size }
    
    fun getClientCount(streamPath: String): Int = streams[streamPath]?.clients?.size ?: 0
    
    fun stop() {
        isRunning.set(false)
        
        streams.values.forEach { stream ->
            stream.clients.values.forEach { client ->
                try {
                    client.rtpSocket?.close()
                    client.socket.close()
                } catch (_: Exception) {}
            }
            stream.clients.clear()
        }
        
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        
        try {
            acceptThread?.join(1000)
        } catch (_: Exception) {}
        
        Log.i(TAG, "Multi-stream RTSP Server stopped")
    }
    
    fun isRunning(): Boolean = isRunning.get()
    
    private data class RTSPRequest(
        val method: String,
        val uri: String,
        val headers: Map<String, String>
    )
    
    data class RTSPClient(
        val id: String,
        val socket: Socket,
        val writer: PrintWriter,
        var sessionId: String = "",
        var rtpPort: Int = 0,
        var rtcpPort: Int = 0,
        var isPlaying: Boolean = false,
        var rtpSocket: DatagramSocket? = null,
        var isTcpMode: Boolean = false,
        // 视频 channel (track0)
        var interleavedRtpChannel: Int = 0,
        var interleavedRtcpChannel: Int = 1,
        // 音频 channel (track1) 
        var audioInterleavedRtpChannel: Int = 2,
        var audioInterleavedRtcpChannel: Int = 3,
        var streamPath: String? = null  // 客户端订阅的流路径
    )
}
