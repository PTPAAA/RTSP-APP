package com.rtsp.camera.model

/**
 * 视频配置
 */
data class VideoConfig(
    val width: Int = 1280,
    val height: Int = 720,
    val bitrate: Int = 1500,      // kbps (提高流畅度)
    val frameRate: Int = 30,      // 提高帧率
    val iFrameInterval: Int = 1   // 关键帧间隔（秒）- 降低延迟
) {
    companion object {
        fun fromPreset(preset: QualityPreset): VideoConfig {
            return VideoConfig(
                width = preset.width,
                height = preset.height,
                bitrate = preset.bitrate,
                frameRate = preset.frameRate
            )
        }
    }
}
