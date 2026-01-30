package com.rtsp.camera.lighting

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕补光管理器
 * 利用手机屏幕作为柔光光源，在低光环境下为前置摄像头补光
 * 
 * 特点：
 * - 半透明补光：不完全遮挡时钟，保持"伪装"效果
 * - 仅前置摄像头使用：后置摄像头应使用闪光灯
 */
class ScreenFlashManager(
    private val window: Window,
    private val overlayView: View
) {
    companion object {
        private const val TAG = "ScreenFlashManager"
        private const val DEFAULT_FLASH_DURATION_MS = 800L
        private const val DEFAULT_INTERVAL_MS = 5000L
        private const val MIN_INTERVAL_MS = 2000L
        
        // 半透明白色，不会完全遮挡时钟
        private const val STEALTH_FLASH_COLOR = 0x80FFFFFF.toInt()  // 50% 透明度白色
        private const val FULL_FLASH_COLOR = 0xFFFFFFFF.toInt()     // 完全白色
    }
    
    // 状态
    private val isEnabled = AtomicBoolean(false)
    private val isFlashing = AtomicBoolean(false)
    private var originalBrightness = -1f
    
    // 配置参数
    var flashDurationMs: Long = DEFAULT_FLASH_DURATION_MS
    var intervalMs: Long = DEFAULT_INTERVAL_MS
    var autoMode: Boolean = true  // 根据光线自动触发
    var lightThreshold: Float = 10f  // 低于此 lux 值时触发补光
    
    // 隐蔽模式：半透明补光，保留时钟可见
    var stealthMode: Boolean = true
    
    // Handler
    private val handler = Handler(Looper.getMainLooper())
    
    // 回调: 在补光亮度稳定时调用（用于同步抓帧）
    var onFlashPeak: (() -> Unit)? = null
    
    // 间歇补光 Runnable
    private val intervalFlashRunnable = object : Runnable {
        override fun run() {
            if (!isEnabled.get()) return
            
            flash()
            handler.postDelayed(this, intervalMs.coerceAtLeast(MIN_INTERVAL_MS))
        }
    }
    
    /**
     * 启用间歇补光模式
     */
    fun enable() {
        if (isEnabled.getAndSet(true)) return
        
        Log.d(TAG, "Screen flash enabled, interval=${intervalMs}ms, stealth=$stealthMode")
        handler.post(intervalFlashRunnable)
    }
    
    /**
     * 禁用补光模式
     */
    fun disable() {
        if (!isEnabled.getAndSet(false)) return
        
        Log.d(TAG, "Screen flash disabled")
        handler.removeCallbacks(intervalFlashRunnable)
        
        // 确保恢复原始状态
        restoreBrightness()
        overlayView.visibility = View.GONE
    }
    
    /**
     * 触发单次补光
     */
    fun flash() {
        if (isFlashing.getAndSet(true)) return
        
        handler.post {
            try {
                // 1. 保存原亮度
                originalBrightness = window.attributes.screenBrightness
                
                // 2. 最大亮度
                val params = window.attributes
                params.screenBrightness = 1.0f
                window.attributes = params
                
                // 3. 显示补光 Overlay（根据模式选择透明度）
                val flashColor = if (stealthMode) STEALTH_FLASH_COLOR else FULL_FLASH_COLOR
                overlayView.setBackgroundColor(flashColor)
                overlayView.visibility = View.VISIBLE
                
                Log.d(TAG, "Flash started (stealth=$stealthMode)")
                
                // 4. 等待亮度稳定后回调（约 100ms）
                handler.postDelayed({
                    onFlashPeak?.invoke()
                }, 100)
                
                // 5. 结束补光
                handler.postDelayed({
                    restoreBrightness()
                    overlayView.visibility = View.GONE
                    isFlashing.set(false)
                    Log.d(TAG, "Flash ended")
                }, flashDurationMs)
                
            } catch (e: Exception) {
                Log.e(TAG, "Flash error", e)
                isFlashing.set(false)
            }
        }
    }
    
    /**
     * 根据光线传感器数据判断是否需要补光
     */
    fun onLightLevelChanged(lux: Float) {
        if (!autoMode) return
        
        if (lux < lightThreshold && !isEnabled.get()) {
            Log.d(TAG, "Low light detected (${lux} lux), enabling flash")
            enable()
        } else if (lux >= lightThreshold * 2 && isEnabled.get()) {
            // 滞回：光线恢复到阈值 2 倍以上才禁用
            Log.d(TAG, "Light restored (${lux} lux), disabling flash")
            disable()
        }
    }
    
    /**
     * 恢复原始亮度
     */
    private fun restoreBrightness() {
        if (originalBrightness >= 0) {
            val params = window.attributes
            params.screenBrightness = originalBrightness
            window.attributes = params
            originalBrightness = -1f
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        disable()
        handler.removeCallbacksAndMessages(null)
    }
}
