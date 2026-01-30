package com.rtsp.camera.encoder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAC音频编码器
 * 使用MediaCodec硬件编码 + AudioRecord录音
 */
class AACEncoder(
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 64000,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) {
    companion object {
        private const val TAG = "AACEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val TIMEOUT_US = 10000L
    }
    
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var encodeThread: Thread? = null
    
    // ADTS header（用于某些播放器）
    var audioSpecificConfig: ByteArray? = null
        private set
    
    // ASC 可用回调
    private var onAscAvailable: ((ByteArray) -> Unit)? = null
    
    private val channelConfig = if (channelCount == 1) 
        AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, 
        channelConfig, 
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2
    
    /**
     * 初始化编码器和录音器
     */
    fun initialize(): Boolean {
        try {
            // 初始化 AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }
            
            // 初始化 MediaCodec AAC 编码器
            val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            
            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            
            Log.d(TAG, "Initialized: ${sampleRate}Hz, ${channelCount}ch, ${bitrate}bps")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            return false
        }
    }
    
    /**
     * 开始录音和编码
     */
    fun start() {
        if (isRunning.get()) return
        
        audioRecord?.startRecording()
        encoder?.start()
        isRunning.set(true)
        
        // 录音线程
        recordThread = Thread({
            recordLoop()
        }, "AACRecordThread").apply { start() }
        
        // 编码输出线程
        encodeThread = Thread({
            encodeLoop()
        }, "AACEncodeThread").apply { start() }
        
        Log.d(TAG, "Audio encoder started")
    }
    
    /**
     * 录音循环 - 从 AudioRecord 读取 PCM 数据并送入编码器
     */
    private fun recordLoop() {
        val buffer = ByteArray(bufferSize)
        
        while (isRunning.get()) {
            try {
                val readBytes = audioRecord?.read(buffer, 0, bufferSize) ?: -1
                
                if (readBytes > 0) {
                    // 获取编码器输入缓冲区
                    val inputIndex = encoder?.dequeueInputBuffer(TIMEOUT_US) ?: -1
                    if (inputIndex >= 0) {
                        val inputBuffer = encoder?.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        inputBuffer?.put(buffer, 0, readBytes)
                        
                        val presentationTimeUs = System.nanoTime() / 1000
                        encoder?.queueInputBuffer(inputIndex, 0, readBytes, presentationTimeUs, 0)
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Record error", e)
                }
            }
        }
    }
    
    /**
     * 编码循环 - 从编码器获取 AAC 数据
     */
    private fun encodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isRunning.get()) {
            try {
                val outputIndex = encoder?.dequeueOutputBuffer(bufferInfo, TIMEOUT_US) ?: -1
                
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 提取 Audio Specific Config (用于 RTSP SDP)
                        val format = encoder?.outputFormat
                        audioSpecificConfig = format?.getByteBuffer("csd-0")?.let { extractBytes(it) }
                        Log.d(TAG, "Output format changed. ASC: ${audioSpecificConfig?.size} bytes")
                        
                        // 通知 ASC 可用
                        audioSpecificConfig?.let { asc ->
                            onAscAvailable?.invoke(asc)
                        }
                    }
                    outputIndex >= 0 -> {
                        val outputBuffer = encoder?.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
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
    
    private fun extractBytes(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }
    
    /**
     * 停止编码
     */
    fun stop() {
        isRunning.set(false)
        
        try {
            recordThread?.join(1000)
            encodeThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Thread join interrupted", e)
        }
        
        try {
            audioRecord?.stop()
            encoder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping", e)
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stop()
        
        try {
            audioRecord?.release()
            audioRecord = null
            encoder?.release()
            encoder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing", e)
        }
        
        audioSpecificConfig = null
        Log.d(TAG, "Audio encoder released")
    }
    
    fun isRunning(): Boolean = isRunning.get()
    
    /**
     * 设置 ASC 可用回调
     * 当 Audio Specific Config 准备好后会调用此回调
     */
    fun setOnAscAvailable(callback: (ByteArray) -> Unit) {
        onAscAvailable = callback
    }
}
