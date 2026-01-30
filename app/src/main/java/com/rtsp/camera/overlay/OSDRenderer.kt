package com.rtsp.camera.overlay

import android.graphics.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * OSD水印渲染器
 * 在视频帧上叠加时间戳、电池电量、设备状态等信息
 */
class OSDRenderer {
    
    companion object {
        private const val DEFAULT_FONT_SIZE = 20f
        private const val PADDING = 12f
        private const val SHADOW_OFFSET = 1.5f
        private const val LINE_SPACING = 6f
    }
    
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = DEFAULT_FONT_SIZE
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
        setShadowLayer(2f, SHADOW_OFFSET, SHADOW_OFFSET, Color.BLACK)
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.argb(100, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    // ========== 显示开关 ==========
    var showTimestamp: Boolean = true
    var showBatteryLevel: Boolean = true
    var showBatteryTemp: Boolean = true
    var showLightLevel: Boolean = false      // 默认关闭
    var showFlashMode: Boolean = false       // 默认关闭
    var showNightVision: Boolean = false     // 默认关闭
    var showRotation: Boolean = false        // 默认关闭
    var showResolution: Boolean = false      // 默认关闭
    var showClientCount: Boolean = false     // 默认关闭
    var showCustomText: String? = null
    
    var fontSize: Float = DEFAULT_FONT_SIZE
        set(value) {
            field = value
            textPaint.textSize = value
        }
    
    // ========== 数据字段 ==========
    // 电池
    var batteryLevel: Int = 100
    var batteryTemperature: Float = 25f
    var isCharging: Boolean = false
    
    // 环境光
    var lightLevel: Float = 0f  // lux
    
    // 模式状态
    var flashMode: String = "OFF"  // OFF/ON/AUTO
    var nightVisionEnabled: Boolean = false
    var rotationDegrees: Int = 0
    
    // 视频信息
    var resolution: String = "1280x720"
    var clientCount: Int = 0
    
    // 带宽信息 (bytes per second)
    var uploadBandwidth: Long = 0    // 上行带宽
    var downloadBandwidth: Long = 0  // 下行带宽
    
    /**
     * 在Bitmap上渲染OSD水印
     */
    fun renderOnBitmap(bitmap: Bitmap): Bitmap {
        val canvas = Canvas(bitmap)
        renderOSD(canvas, bitmap.width, bitmap.height)
        return bitmap
    }
    
    /**
     * 创建带OSD的新Bitmap
     */
    fun createOSDBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT)
        renderOSD(canvas, width, height)
        return bitmap
    }
    
    /**
     * 渲染OSD到Canvas
     */
    fun renderOSD(canvas: Canvas, width: Int, height: Int) {
        val lines = mutableListOf<String>()
        
        // 时间戳
        if (showTimestamp) {
            lines.add(dateFormat.format(Date()))
        }
        
        // 电池信息（紧随时间戳之后）
        if (showBatteryLevel || showBatteryTemp) {
            val chargingStr = if (isCharging) "[CHG]" else ""
            val levelStr = if (showBatteryLevel) "BAT:$batteryLevel%$chargingStr" else ""
            val tempStr = if (showBatteryTemp) " ${String.format("%.1f", batteryTemperature)}C" else ""
            lines.add("$levelStr$tempStr")
        }
        
        // 分辨率 + 客户端 + 带宽（可选）
        if (showResolution || showClientCount) {
            val resStr = if (showResolution) resolution else ""
            val clientStr = if (showClientCount) " [$clientCount]" else ""
            val combined = "$resStr$clientStr".trim()
            if (combined.isNotEmpty()) lines.add(combined)
        }
        
        // 带宽显示（始终显示当有客户端时）
        if (uploadBandwidth > 0 || downloadBandwidth > 0) {
            val upMbps = String.format("%.2f", uploadBandwidth * 8 / 1_000_000.0)
            val downMbps = String.format("%.2f", downloadBandwidth * 8 / 1_000_000.0)
            lines.add("↑${upMbps}Mbps ↓${downMbps}Mbps")
        }
        
        // 环境光（可选）
        if (showLightLevel) {
            lines.add("LUX:${String.format("%.0f", lightLevel)}")
        }
        
        // 模式状态（可选）
        val statusParts = mutableListOf<String>()
        if (showFlashMode) {
            val flashStr = when (flashMode) {
                "ON" -> "LED:ON"
                "AUTO" -> "LED:A"
                else -> "LED:-"
            }
            statusParts.add(flashStr)
        }
        if (showNightVision && nightVisionEnabled) {
            statusParts.add("NV:ON")
        }
        if (showRotation && rotationDegrees != 0) {
            statusParts.add("ROT:$rotationDegrees")
        }
        if (statusParts.isNotEmpty()) {
            lines.add(statusParts.joinToString(" "))
        }
        
        // 自定义文本
        showCustomText?.let { lines.add(it) }
        
        if (lines.isEmpty()) return
        
        // 计算文本位置 (左上角)
        var y = PADDING + textPaint.textSize
        
        for (line in lines) {
            if (line.isBlank()) continue
            
            // 绘制背景
            val textWidth = textPaint.measureText(line)
            val bgRect = RectF(
                PADDING - 4,
                y - textPaint.textSize,
                PADDING + textWidth + 4,
                y + 4
            )
            canvas.drawRoundRect(bgRect, 4f, 4f, backgroundPaint)
            
            // 绘制文本
            canvas.drawText(line, PADDING, y, textPaint)
            
            y += textPaint.textSize + LINE_SPACING
        }
    }
    
    /**
     * 获取OSD叠加层的像素数据（用于与视频帧合成）
     */
    fun getOSDOverlayData(width: Int, height: Int): IntArray {
        val bitmap = createOSDBitmap(width, height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return pixels
    }
}

