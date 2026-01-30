package com.rtsp.camera.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.renderscript.*
import kotlin.math.max
import kotlin.math.min

/**
 * 夜视增强处理器
 * 通过直方图均衡化、增益调整和降噪提升暗光画质
 */
class NightVisionProcessor {
    
    companion object {
        private const val DEFAULT_BRIGHTNESS_BOOST = 1.5f
        private const val DEFAULT_CONTRAST_BOOST = 1.2f
        private const val DEFAULT_DENOISE_STRENGTH = 0.3f
    }
    
    // 处理参数
    var enabled: Boolean = false
    var brightnessBoost: Float = DEFAULT_BRIGHTNESS_BOOST
    var contrastBoost: Float = DEFAULT_CONTRAST_BOOST
    var denoiseStrength: Float = DEFAULT_DENOISE_STRENGTH
    var autoAdjust: Boolean = true  // 根据画面亮度自动调整增强程度
    
    /**
     * 处理YUV帧数据（NV21格式）
     * 直接修改Y通道来调整亮度/对比度
     */
    fun processYUV(yuvData: ByteArray, width: Int, height: Int) {
        if (!enabled) return
        
        val ySize = width * height
        
        // 计算平均亮度
        var avgBrightness = 0L
        for (i in 0 until ySize) {
            avgBrightness += (yuvData[i].toInt() and 0xFF)
        }
        avgBrightness /= ySize
        
        // 自动调整增强程度
        val effectiveBrightness: Float
        val effectiveContrast: Float
        
        if (autoAdjust) {
            // 越暗，增强越多
            val darkFactor = max(0f, 1f - avgBrightness / 128f)
            effectiveBrightness = 1f + (brightnessBoost - 1f) * darkFactor
            effectiveContrast = 1f + (contrastBoost - 1f) * darkFactor
        } else {
            effectiveBrightness = brightnessBoost
            effectiveContrast = contrastBoost
        }
        
        // 应用亮度/对比度调整
        val midPoint = 128
        for (i in 0 until ySize) {
            var y = yuvData[i].toInt() and 0xFF
            
            // 亮度增强
            y = (y * effectiveBrightness).toInt()
            
            // 对比度增强 (以128为中点)
            y = ((y - midPoint) * effectiveContrast + midPoint).toInt()
            
            // 钳制到有效范围
            y = y.coerceIn(0, 255)
            
            yuvData[i] = y.toByte()
        }
        
        // 简单降噪 - 可选
        if (denoiseStrength > 0) {
            applySimpleDenoise(yuvData, width, height, denoiseStrength)
        }
    }
    
    /**
     * 处理ARGB Bitmap
     */
    fun processBitmap(bitmap: Bitmap): Bitmap {
        if (!enabled) return bitmap
        
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // 计算平均亮度
        var avgBrightness = 0L
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            avgBrightness += (r + g + b) / 3
        }
        avgBrightness /= pixels.size
        
        // 自动调整增强程度
        val effectiveBrightness: Float
        val effectiveContrast: Float
        
        if (autoAdjust) {
            val darkFactor = max(0f, 1f - avgBrightness / 128f)
            effectiveBrightness = 1f + (brightnessBoost - 1f) * darkFactor
            effectiveContrast = 1f + (contrastBoost - 1f) * darkFactor
        } else {
            effectiveBrightness = brightnessBoost
            effectiveContrast = contrastBoost
        }
        
        // 处理每个像素
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = Color.alpha(pixel)
            var r = Color.red(pixel)
            var g = Color.green(pixel)
            var b = Color.blue(pixel)
            
            // 亮度增强
            r = (r * effectiveBrightness).toInt()
            g = (g * effectiveBrightness).toInt()
            b = (b * effectiveBrightness).toInt()
            
            // 对比度增强
            r = ((r - 128) * effectiveContrast + 128).toInt()
            g = ((g - 128) * effectiveContrast + 128).toInt()
            b = ((b - 128) * effectiveContrast + 128).toInt()
            
            // 钳制
            r = r.coerceIn(0, 255)
            g = g.coerceIn(0, 255)
            b = b.coerceIn(0, 255)
            
            pixels[i] = Color.argb(a, r, g, b)
        }
        
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }
    
    /**
     * 简单的3x3均值降噪
     */
    private fun applySimpleDenoise(yuvData: ByteArray, width: Int, height: Int, strength: Float) {
        val ySize = width * height
        val temp = ByteArray(ySize)
        System.arraycopy(yuvData, 0, temp, 0, ySize)
        
        val blendOriginal = 1f - strength
        val blendFiltered = strength
        
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                
                // 3x3邻域均值
                var sum = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += temp[(y + dy) * width + (x + dx)].toInt() and 0xFF
                    }
                }
                val avg = sum / 9
                
                // 混合原始值和滤波值
                val original = temp[idx].toInt() and 0xFF
                val result = (original * blendOriginal + avg * blendFiltered).toInt()
                
                yuvData[idx] = result.coerceIn(0, 255).toByte()
            }
        }
    }
    
    /**
     * 直方图均衡化（更强的增强效果）
     */
    fun applyHistogramEqualization(yuvData: ByteArray, width: Int, height: Int) {
        if (!enabled) return
        
        val ySize = width * height
        
        // 统计直方图
        val histogram = IntArray(256)
        for (i in 0 until ySize) {
            histogram[yuvData[i].toInt() and 0xFF]++
        }
        
        // 计算累积分布函数 (CDF)
        val cdf = IntArray(256)
        cdf[0] = histogram[0]
        for (i in 1 until 256) {
            cdf[i] = cdf[i - 1] + histogram[i]
        }
        
        // 找到CDF最小非零值
        var cdfMin = 0
        for (i in 0 until 256) {
            if (cdf[i] > 0) {
                cdfMin = cdf[i]
                break
            }
        }
        
        // 创建查找表
        val lut = IntArray(256)
        val denominator = ySize - cdfMin
        if (denominator > 0) {
            for (i in 0 until 256) {
                lut[i] = ((cdf[i] - cdfMin) * 255 / denominator).coerceIn(0, 255)
            }
        }
        
        // 应用查找表
        for (i in 0 until ySize) {
            yuvData[i] = lut[yuvData[i].toInt() and 0xFF].toByte()
        }
    }
}
