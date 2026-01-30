package com.rtsp.camera.model

/**
 * 推流状态
 */
sealed class StreamingState {
    object Idle : StreamingState()
    object Starting : StreamingState()
    data class Streaming(val rtspUrl: String, val clientCount: Int = 0) : StreamingState()
    data class Error(val message: String) : StreamingState()
    object Stopping : StreamingState()
}
