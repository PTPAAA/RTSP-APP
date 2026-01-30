package com.rtsp.camera.renderer

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.rtsp.camera.overlay.OSDRenderer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 视频渲染管线管理器
 * 协调相机、OSD渲染和编码器之间的数据流
 * 
 * 数据流向：
 * 相机 → GLCameraRenderer → 编码器
 *              ↓
 *         OSD 纹理叠加
 */
class VideoRenderPipeline(
    private val context: Context,
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "VideoRenderPipeline"
        private const val OSD_UPDATE_INTERVAL_MS = 1000L // OSD 更新间隔
    }
    
    // 渲染器
    private var glRenderer: GLCameraRenderer? = null
    private val osdRenderer = OSDRenderer()
    
    // OSD Bitmap 缓存
    private var osdBitmap: Bitmap? = null
    
    // 渲染线程
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null
    
    // OSD 更新线程
    private var osdUpdateThread: HandlerThread? = null
    private var osdUpdateHandler: Handler? = null
    
    // 状态
    private val isRunning = AtomicBoolean(false)
    private var osdEnabled = true
    
    // 画面旋转角度 (0, 90, 180, 270)
    private var rotationDegrees = 0
    
    // 电池信息 (原子引用保证线程安全)
    private val batteryLevel = AtomicReference(100)
    private val batteryTemp = AtomicReference(25.0f)
    private val isCharging = AtomicBoolean(false)
    
    // OSD 新增参数
    private val lightLevel = AtomicReference(0f)  // 环境光 lux
    private val flashMode = AtomicReference("OFF")  // OFF/ON/AUTO
    private val nightVisionEnabled = AtomicBoolean(false)
    private val resolution = AtomicReference("${width}x${height}")
    private val clientCount = AtomicReference(0)
    
    // 带宽信息 (bytes per second)
    private val uploadBandwidth = AtomicReference(0L)
    private val downloadBandwidth = AtomicReference(0L)
    
    /**
     * 初始化渲染管线
     * @param encoderSurface 编码器的输入 Surface
     * @return 相机应该输出到的 Surface
     */
    fun initialize(encoderSurface: Surface): Surface {
        Log.d(TAG, "Initializing video render pipeline: ${width}x${height}")
        
        // 创建渲染线程
        renderThread = HandlerThread("RenderThread").apply { start() }
        renderHandler = Handler(renderThread!!.looper)
        
        // 创建 OSD 更新线程
        osdUpdateThread = HandlerThread("OSDUpdateThread").apply { start() }
        osdUpdateHandler = Handler(osdUpdateThread!!.looper)
        
        // 在渲染线程上初始化 GL 渲染器
        var cameraSurface: Surface? = null
        val initLatch = java.util.concurrent.CountDownLatch(1)
        
        renderHandler?.post {
            try {
                glRenderer = GLCameraRenderer(width, height)
                cameraSurface = glRenderer?.initialize(encoderSurface)
                
                // 设置帧可用回调
                glRenderer?.setOnFrameAvailableListener {
                    renderHandler?.post {
                        drawFrame()
                    }
                }
                
                Log.d(TAG, "GL renderer initialized on render thread")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GL renderer", e)
            }
            initLatch.countDown()
        }
        
        initLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        
        if (cameraSurface == null) {
            throw RuntimeException("Failed to get camera surface from GL renderer")
        }
        
        // 初始化 OSD Bitmap
        osdBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        
        isRunning.set(true)
        
        // 启动 OSD 更新任务
        startOsdUpdate()
        
        return cameraSurface!!
    }
    
    /**
     * 绘制一帧（在渲染线程调用）
     */
    private fun drawFrame() {
        if (!isRunning.get()) return
        
        try {
            glRenderer?.drawFrame()
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing frame", e)
        }
    }
    
    /**
     * 启动 OSD 定时更新
     */
    private fun startOsdUpdate() {
        val updateRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                
                try {
                    updateOsd()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating OSD", e)
                }
                
                osdUpdateHandler?.postDelayed(this, OSD_UPDATE_INTERVAL_MS)
            }
        }
        
        osdUpdateHandler?.post(updateRunnable)
    }
    
    /**
     * 更新 OSD 内容
     */
    private fun updateOsd() {
        if (!osdEnabled) return
        
        val bitmap = osdBitmap ?: return
        
        // 更新 OSD 渲染器的电池信息
        osdRenderer.batteryLevel = batteryLevel.get()
        osdRenderer.batteryTemperature = batteryTemp.get()
        osdRenderer.isCharging = isCharging.get()
        
        // 更新新增 OSD 参数
        osdRenderer.lightLevel = lightLevel.get()
        osdRenderer.flashMode = flashMode.get()
        osdRenderer.nightVisionEnabled = nightVisionEnabled.get()
        osdRenderer.rotationDegrees = rotationDegrees
        osdRenderer.resolution = resolution.get()
        osdRenderer.clientCount = clientCount.get()
        
        // 更新带宽信息
        osdRenderer.uploadBandwidth = uploadBandwidth.get()
        osdRenderer.downloadBandwidth = downloadBandwidth.get()
        
        // 清空并重新渲染 OSD
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        osdRenderer.renderOnBitmap(bitmap)
        
        // 在渲染线程更新纹理
        renderHandler?.post {
            glRenderer?.updateOsdTexture(bitmap)
        }
    }
    
    /**
     * 设置 OSD 是否启用
     */
    fun setOsdEnabled(enabled: Boolean) {
        osdEnabled = enabled
        glRenderer?.setOsdEnabled(enabled)
    }
    
    /**
     * 设置预览 Surface（用于显示 OSD 处理后的画面）
     * @param surface 预览 Surface，null 表示移除预览
     */
    fun setPreviewSurface(surface: android.view.Surface?) {
        renderHandler?.post {
            glRenderer?.setPreviewSurface(surface)
        }
    }
    
    /**
     * 设置画面旋转角度
     * @param degrees 旋转角度，支持 0, 90, 180, 270
     */
    fun setRotation(degrees: Int) {
        rotationDegrees = degrees
        renderHandler?.post {
            glRenderer?.setRotation(degrees)
        }
    }
    
    /**
     * 设置夜视增强
     * @param enabled 是否启用
     * @param brightness 亮度增强 (1.0 = 正常)
     * @param contrast 对比度增强 (1.0 = 正常)
     */
    fun setNightVision(enabled: Boolean, brightness: Float = 2.5f, contrast: Float = 1.4f) {
        renderHandler?.post {
            glRenderer?.setNightVision(enabled, brightness, contrast)
        }
    }
    
    /**
     * 设置画面缩放（用于手动调整宽高比）
     * @param scaleX 水平缩放 (1.0 = 正常)
     * @param scaleY 垂直缩放 (1.0 = 正常)
     */
    fun setScale(scaleX: Float, scaleY: Float) {
        renderHandler?.post {
            glRenderer?.setScale(scaleX, scaleY)
        }
    }
    
    /**
     * 设置低光增强
     * @param enabled 是否启用
     * @param denoise 降噪强度 (0.0-1.0)
     * @param exposure 曝光补偿 (1.0-4.0)
     * @param contrast 局部对比度 (0.0-1.0)
     */
    fun setLowLightEnhancement(
        enabled: Boolean,
        denoise: Float = 0.5f,
        exposure: Float = 2.0f,
        contrast: Float = 0.3f
    ) {
        renderHandler?.post {
            glRenderer?.setLowLightEnhancement(enabled, denoise, exposure, contrast)
        }
        // 更新 OSD 状态
        updateLowLightStatus(enabled)
    }
    
    /**
     * 更新低光增强状态 (用于 OSD 显示)
     */
    private fun updateLowLightStatus(enabled: Boolean) {
        // 可以在这里添加 OSD 显示低光增强状态的逻辑
    }
    
    /**
     * 更新电池信息
     */
    fun updateBatteryInfo(level: Int, temperature: Float, charging: Boolean) {
        batteryLevel.set(level)
        batteryTemp.set(temperature)
        isCharging.set(charging)
    }
    
    /**
     * 更新环境光照度
     */
    fun updateLightLevel(lux: Float) {
        lightLevel.set(lux)
    }
    
    /**
     * 更新闪光灯模式
     */
    fun updateFlashMode(mode: String) {
        flashMode.set(mode)
    }
    
    /**
     * 更新夜视状态
     */
    fun updateNightVision(enabled: Boolean) {
        nightVisionEnabled.set(enabled)
    }
    
    /**
     * 更新客户端连接数
     */
    fun updateClientCount(count: Int) {
        clientCount.set(count)
    }
    
    /**
     * 更新带宽信息
     */
    fun updateBandwidth(upload: Long, download: Long) {
        uploadBandwidth.set(upload)
        downloadBandwidth.set(download)
    }
    
    /**
     * 设置自定义 OSD 文本
     */
    fun setCustomText(text: String?) {
        osdRenderer.showCustomText = text
    }
    
    /**
     * 配置 OSD 显示选项
     */
    fun configureOsd(
        showTimestamp: Boolean = true,
        showBattery: Boolean = true,
        showTemp: Boolean = true,
        fontSize: Float = 24f
    ) {
        osdRenderer.showTimestamp = showTimestamp
        osdRenderer.showBatteryLevel = showBattery
        osdRenderer.showBatteryTemp = showTemp
        osdRenderer.fontSize = fontSize
    }
    
    /**
     * 停止渲染管线
     */
    fun stop() {
        isRunning.set(false)
        
        // 清理 OSD 更新线程
        osdUpdateHandler?.removeCallbacksAndMessages(null)
        osdUpdateThread?.quitSafely()
        osdUpdateThread = null
        osdUpdateHandler = null
        
        // 在渲染线程释放 GL 资源
        val releaseLatch = java.util.concurrent.CountDownLatch(1)
        renderHandler?.post {
            glRenderer?.release()
            glRenderer = null
            releaseLatch.countDown()
        }
        
        try {
            releaseLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Timeout waiting for GL release")
        }
        
        // 清理渲染线程
        renderHandler?.removeCallbacksAndMessages(null)
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
        
        // 回收 Bitmap
        osdBitmap?.recycle()
        osdBitmap = null
        
        Log.d(TAG, "Video render pipeline stopped")
    }
}
