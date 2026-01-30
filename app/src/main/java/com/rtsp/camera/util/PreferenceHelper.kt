package com.rtsp.camera.util

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.rtsp.camera.model.FlashMode
import com.rtsp.camera.model.NetworkConfig
import com.rtsp.camera.model.QualityPreset
import com.rtsp.camera.model.VideoConfig

/**
 * 应用偏好设置管理器 (增强版)
 * 支持多码流、OSD水印、夜视增强等功能设置
 */
class PreferenceHelper(context: Context) {
    
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    
    companion object {
        // 视频设置
        private const val KEY_QUALITY_PRESET = "quality_preset"
        private const val KEY_CUSTOM_WIDTH = "custom_width"
        private const val KEY_CUSTOM_HEIGHT = "custom_height"
        private const val KEY_CUSTOM_BITRATE = "custom_bitrate"
        private const val KEY_CUSTOM_FRAMERATE = "custom_framerate"
        
        // 音频设置
        private const val KEY_ENABLE_AUDIO = "enable_audio"
        
        // 网络设置
        private const val KEY_RTSP_PORT = "rtsp_port"
        private const val KEY_IPV4_ONLY = "ipv4_only"
        private const val KEY_LAN_ONLY = "lan_only"
        
        // RTSP 鉴权
        private const val KEY_ENABLE_AUTH = "enable_auth"
        private const val KEY_AUTH_USERNAME = "auth_username"
        private const val KEY_AUTH_PASSWORD = "auth_password"
        
        // 闪光灯
        private const val KEY_FLASH_MODE = "flash_mode"
        private const val KEY_AUTO_FLASH_THRESHOLD = "auto_flash_threshold"
        
        // OSD水印设置
        private const val KEY_ENABLE_OSD = "enable_osd"
        private const val KEY_OSD_SHOW_TIMESTAMP = "osd_show_timestamp"
        private const val KEY_OSD_SHOW_BATTERY = "osd_show_battery"
        private const val KEY_OSD_SHOW_TEMP = "osd_show_temp"
        private const val KEY_OSD_CUSTOM_TEXT = "osd_custom_text"
        private const val KEY_OSD_FONT_SIZE = "osd_font_size"
        
        // 夜视增强设置
        private const val KEY_ENABLE_NIGHT_VISION = "enable_night_vision"
        private const val KEY_NIGHT_VISION_AUTO = "night_vision_auto"
        private const val KEY_NIGHT_VISION_BRIGHTNESS = "night_vision_brightness"
        private const val KEY_NIGHT_VISION_CONTRAST = "night_vision_contrast"
        private const val KEY_NIGHT_VISION_DENOISE = "night_vision_denoise"
        
        // 高级设置
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_USE_BACK_CAMERA = "use_back_camera"
        private const val KEY_ENABLE_KEEP_ALIVE = "enable_keep_alive"
        private const val KEY_ROTATION_DEGREES = "rotation_degrees"
        
        // 温度传感器
        private const val KEY_TEMP_SENSOR = "temp_sensor"
    }
    
    // ========== 视频设置 ==========
    
    var qualityPreset: QualityPreset
        get() {
            // ListPreference 使用 String 存储，需要处理类型兼容
            return try {
                val stringValue = prefs.getString(KEY_QUALITY_PRESET, QualityPreset.MEDIUM.ordinal.toString())
                val ordinal = stringValue?.toIntOrNull() ?: QualityPreset.MEDIUM.ordinal
                QualityPreset.fromOrdinal(ordinal)
            } catch (e: ClassCastException) {
                // 处理旧版本存储的 Int 类型值 - 迁移到 String
                val intValue = prefs.getInt(KEY_QUALITY_PRESET, QualityPreset.MEDIUM.ordinal)
                prefs.edit().remove(KEY_QUALITY_PRESET).putString(KEY_QUALITY_PRESET, intValue.toString()).apply()
                QualityPreset.fromOrdinal(intValue)
            }
        }
        set(value) = prefs.edit().putString(KEY_QUALITY_PRESET, value.ordinal.toString()).apply()
    
    var customWidth: Int
        get() = prefs.getString(KEY_CUSTOM_WIDTH, "1280")?.toIntOrNull() ?: 1280
        set(value) = prefs.edit().putString(KEY_CUSTOM_WIDTH, value.toString()).apply()
    
    var customHeight: Int
        get() = prefs.getString(KEY_CUSTOM_HEIGHT, "720")?.toIntOrNull() ?: 720
        set(value) = prefs.edit().putString(KEY_CUSTOM_HEIGHT, value.toString()).apply()
    
    var customBitrate: Int
        get() = prefs.getString(KEY_CUSTOM_BITRATE, "1000")?.toIntOrNull() ?: 1000
        set(value) = prefs.edit().putString(KEY_CUSTOM_BITRATE, value.toString()).apply()
    
    var customFrameRate: Int
        get() = prefs.getString(KEY_CUSTOM_FRAMERATE, "24")?.toIntOrNull() ?: 24
        set(value) = prefs.edit().putString(KEY_CUSTOM_FRAMERATE, value.toString()).apply()
    
    // ========== 音频设置 ==========
    
    var enableAudio: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AUDIO, value).apply()
    
    fun getVideoConfig(): VideoConfig {
        val preset = qualityPreset
        return if (preset == QualityPreset.CUSTOM) {
            VideoConfig(
                width = customWidth,
                height = customHeight,
                bitrate = customBitrate,
                frameRate = customFrameRate
            )
        } else {
            VideoConfig.fromPreset(preset)
        }
    }
    
    // ========== 网络设置 ==========
    
    var rtspPort: Int
        get() = prefs.getString(KEY_RTSP_PORT, "8554")?.toIntOrNull() ?: 8554
        set(value) = prefs.edit().putString(KEY_RTSP_PORT, value.toString()).apply()
    
    var ipv4Only: Boolean
        get() = prefs.getBoolean(KEY_IPV4_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_IPV4_ONLY, value).apply()
    
    var lanOnly: Boolean
        get() = prefs.getBoolean(KEY_LAN_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_LAN_ONLY, value).apply()
    
    fun getNetworkConfig(): NetworkConfig {
        return NetworkConfig(
            port = rtspPort,
            ipv4Only = ipv4Only,
            lanOnly = lanOnly,
            enableAuth = enableAuth,
            username = authUsername,
            password = authPassword
        )
    }
    
    // ========== RTSP 鉴权设置 ==========
    
    var enableAuth: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_AUTH, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_AUTH, value).apply()
    
    var authUsername: String
        get() = prefs.getString(KEY_AUTH_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_USERNAME, value).apply()
    
    var authPassword: String
        get() = prefs.getString(KEY_AUTH_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_PASSWORD, value).apply()
    
    // ========== 闪光灯设置 ==========
    
    var flashMode: FlashMode
        get() = FlashMode.entries.getOrElse(prefs.getInt(KEY_FLASH_MODE, FlashMode.OFF.ordinal)) { FlashMode.OFF }
        set(value) = prefs.edit().putInt(KEY_FLASH_MODE, value.ordinal).apply()
    
    var autoFlashThreshold: Float
        get() = prefs.getString(KEY_AUTO_FLASH_THRESHOLD, "10")?.toFloatOrNull() ?: 10f
        set(value) = prefs.edit().putString(KEY_AUTO_FLASH_THRESHOLD, value.toString()).apply()
    
    // ========== OSD水印设置 ==========
    
    var enableOSD: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_OSD, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_OSD, value).apply()
    
    var osdShowTimestamp: Boolean
        get() = prefs.getBoolean(KEY_OSD_SHOW_TIMESTAMP, true)
        set(value) = prefs.edit().putBoolean(KEY_OSD_SHOW_TIMESTAMP, value).apply()
    
    var osdShowBattery: Boolean
        get() = prefs.getBoolean(KEY_OSD_SHOW_BATTERY, true)
        set(value) = prefs.edit().putBoolean(KEY_OSD_SHOW_BATTERY, value).apply()
    
    var osdShowTemp: Boolean
        get() = prefs.getBoolean(KEY_OSD_SHOW_TEMP, true)
        set(value) = prefs.edit().putBoolean(KEY_OSD_SHOW_TEMP, value).apply()
    
    var osdCustomText: String?
        get() = prefs.getString(KEY_OSD_CUSTOM_TEXT, null)
        set(value) = prefs.edit().putString(KEY_OSD_CUSTOM_TEXT, value).apply()
    
    var osdFontSize: Int
        get() = prefs.getInt(KEY_OSD_FONT_SIZE, 24)
        set(value) = prefs.edit().putInt(KEY_OSD_FONT_SIZE, value).apply()
    
    /**
     * OSD配置数据类
     */
    data class OSDConfig(
        val enabled: Boolean,
        val showTimestamp: Boolean,
        val showBattery: Boolean,
        val showTemp: Boolean,
        val customText: String?,
        val fontSize: Float
    )
    
    fun getOSDConfig(): OSDConfig {
        return OSDConfig(
            enabled = enableOSD,
            showTimestamp = osdShowTimestamp,
            showBattery = osdShowBattery,
            showTemp = osdShowTemp,
            customText = osdCustomText,
            fontSize = osdFontSize.toFloat()
        )
    }
    
    // ========== 夜视增强设置 ==========
    
    var enableNightVision: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_NIGHT_VISION, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_NIGHT_VISION, value).apply()
    
    var nightVisionAuto: Boolean
        get() = prefs.getBoolean(KEY_NIGHT_VISION_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT_VISION_AUTO, value).apply()
    
    var nightVisionBrightness: Float
        get() = prefs.getInt(KEY_NIGHT_VISION_BRIGHTNESS, 150) / 100f
        set(value) = prefs.edit().putInt(KEY_NIGHT_VISION_BRIGHTNESS, (value * 100).toInt()).apply()
    
    var nightVisionContrast: Float
        get() = prefs.getInt(KEY_NIGHT_VISION_CONTRAST, 120) / 100f
        set(value) = prefs.edit().putInt(KEY_NIGHT_VISION_CONTRAST, (value * 100).toInt()).apply()
    
    var nightVisionDenoise: Float
        get() = prefs.getInt(KEY_NIGHT_VISION_DENOISE, 30) / 100f
        set(value) = prefs.edit().putInt(KEY_NIGHT_VISION_DENOISE, (value * 100).toInt()).apply()
    
    /**
     * 夜视增强配置数据类
     */
    data class NightVisionConfig(
        val enabled: Boolean,
        val autoAdjust: Boolean,
        val brightnessBoost: Float,
        val contrastBoost: Float,
        val denoiseStrength: Float
    )
    
    fun getNightVisionConfig(): NightVisionConfig {
        return NightVisionConfig(
            enabled = enableNightVision,
            autoAdjust = nightVisionAuto,
            brightnessBoost = nightVisionBrightness,
            contrastBoost = nightVisionContrast,
            denoiseStrength = nightVisionDenoise
        )
    }
    
    // ========== 高级设置 ==========
    
    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_START, value).apply()
    
    var useBackCamera: Boolean
        get() = prefs.getBoolean(KEY_USE_BACK_CAMERA, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_BACK_CAMERA, value).apply()
    
    var enableKeepAlive: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_KEEP_ALIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLE_KEEP_ALIVE, value).apply()
    
    var rotationDegrees: Int
        get() = prefs.getInt(KEY_ROTATION_DEGREES, 0)
        set(value) = prefs.edit().putInt(KEY_ROTATION_DEGREES, value).apply()
    
    // ========== 温度传感器设置 ==========
    
    var tempSensor: String
        get() = prefs.getString(KEY_TEMP_SENSOR, "battery") ?: "battery"
        set(value) = prefs.edit().putString(KEY_TEMP_SENSOR, value).apply()
}
