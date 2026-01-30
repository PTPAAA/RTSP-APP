package com.rtsp.camera.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.rtsp.camera.model.VideoConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264视频编码器
 * 使用MediaCodec硬件编码
 */
class H264Encoder(
    private val config: VideoConfig,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10000L
    }
    
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null
    
    // SPS/PPS数据（用于RTSP）
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set
    
    /**
     * 初始化编码器
     * @return 用于Camera输入的Surface
     */
    fun initialize(): Surface {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate * 1000) // kbps -> bps
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.iFrameInterval)
            
            // 设置编码配置 (兼容性优先)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            
            // 低延迟优化 (骁龙660+)
            try {
                setInteger(MediaFormat.KEY_LATENCY, 0)  // 最低延迟
                setInteger(MediaFormat.KEY_PRIORITY, 0)  // 实时优先级
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)  // 禁用B帧
            } catch (e: Exception) {
                Log.w(TAG, "Low latency settings not supported: ${e.message}")
            }
        }
        
        encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
        }
        
        return inputSurface!!
    }
    
    /**
     * 开始编码
     */
    fun start() {
        if (isRunning.get()) return
        
        encoder?.start()
        isRunning.set(true)
        
        encoderThread = Thread({
            encodeLoop()
        }, "H264EncoderThread").apply { start() }
        
        Log.d(TAG, "Encoder started: ${config.width}x${config.height} @ ${config.bitrate}kbps")
    }
    
    /**
     * 编码循环
     */
    private fun encodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isRunning.get()) {
            try {
                val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: -1
                
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 提取SPS/PPS
                        val format = encoder?.outputFormat
                        sps = format?.getByteBuffer("csd-0")?.let { extractBytes(it) }
                        pps = format?.getByteBuffer("csd-1")?.let { extractBytes(it) }
                        Log.d(TAG, "Output format changed. SPS: ${sps?.size} bytes, PPS: ${pps?.size} bytes")
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // 调整buffer位置
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            
                            onEncodedFrame(outputBuffer, bufferInfo)
                        }
                        encoder?.releaseOutputBuffer(outputIndex, false)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Encode error", e)
                }
            }
        }
    }
    
    /**
     * 从ByteBuffer提取字节数组
     */
    private fun extractBytes(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
    
    /**
     * 请求关键帧
     */
    fun requestKeyFrame() {
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            encoder?.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request key frame", e)
        }
    }
    
    /**
     * 停止编码
     */
    fun stop() {
        isRunning.set(false)
        
        try {
            encoderThread?.join(1000)
            encoderThread = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Encoder thread join interrupted", e)
        }
        
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        
        try {
            inputSurface?.release()
            inputSurface = null
            encoder?.release()
            encoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
        
        sps = null
        pps = null
        
        Log.d(TAG, "Encoder released")
    }
    
    /**
     * 编码器是否正在运行
     */
    fun isRunning(): Boolean = isRunning.get()
}
