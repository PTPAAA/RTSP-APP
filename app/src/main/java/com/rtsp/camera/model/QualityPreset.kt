package com.rtsp.camera.model

/**
 * 视频画质预设
 */
enum class QualityPreset(
    val displayName: String,
    val width: Int,
    val height: Int,
    val bitrate: Int,    // kbps
    val frameRate: Int
) {
    VERY_LOW("极低", 320, 240, 128, 10),
    LOW("低", 640, 480, 512, 15),
    MEDIUM("中", 1280, 720, 1000, 24),
    HIGH("高", 1920, 1080, 2000, 30),
    CUSTOM("自定义", 0, 0, 0, 0);
    
    companion object {
        fun fromOrdinal(ordinal: Int): QualityPreset {
            return entries.getOrElse(ordinal) { MEDIUM }
        }
    }
}
