package com.rtsp.camera.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rtsp.camera.R
import com.rtsp.camera.RTSPCameraApp
import com.rtsp.camera.camera.CameraManager
import com.rtsp.camera.encoder.AACEncoder
import com.rtsp.camera.encoder.H264Encoder
import com.rtsp.camera.keepalive.KeepAliveManager
import com.rtsp.camera.model.FlashMode
import com.rtsp.camera.model.StreamingState
import com.rtsp.camera.model.VideoConfig
import com.rtsp.camera.overlay.OSDRenderer
import com.rtsp.camera.processing.NightVisionProcessor
import com.rtsp.camera.rtsp.MultiStreamRTSPServer
import com.rtsp.camera.renderer.VideoRenderPipeline
import com.rtsp.camera.sensor.LightSensorManager
import com.rtsp.camera.ui.MainActivity
import com.rtsp.camera.util.BatteryMonitor
import com.rtsp.camera.util.NetworkUtils
import com.rtsp.camera.util.PreferenceHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.nio.ByteBuffer

/**
 * RTSP推流前台服务 (增强版)
 * 支持多码流、OSD水印、夜视增强、高级保活
 */
class RTSPService : Service() {
    
    companion object {
        private const val TAG = "RTSPService"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START = "com.rtsp.camera.action.START"
        const val ACTION_STOP = "com.rtsp.camera.action.STOP"
        
        // 单码流路径
        const val STREAM_LIVE = "live"
    }
    
    // 服务Binder
    inner class LocalBinder : Binder() {
        fun getService(): RTSPService = this@RTSPService
    }
    
    private val binder = LocalBinder()
    
    // 组件
    private lateinit var prefs: PreferenceHelper
    private var cameraManager: CameraManager? = null
    private var mainEncoder: H264Encoder? = null
    private var audioEncoder: AACEncoder? = null
    private var multiStreamServer: MultiStreamRTSPServer? = null
    private var lightSensorManager: LightSensorManager? = null
    
    // 新增组件
    private var keepAliveManager: KeepAliveManager? = null
    private var batteryMonitor: BatteryMonitor? = null
    private var osdRenderer: OSDRenderer? = null
    private var nightVisionProcessor: NightVisionProcessor? = null
    private var mainRenderPipeline: VideoRenderPipeline? = null
    
    // 协程
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 状态
    private val _streamingState = MutableStateFlow<StreamingState>(StreamingState.Idle)
    val streamingState: StateFlow<StreamingState> = _streamingState
    
    private var currentFlashMode: FlashMode = FlashMode.OFF
    
    // 配置
    var enableOSD: Boolean = true
    var enableNightVision: Boolean = false
    
    // 预览 Surface（推流时显示 OSD 处理后的画面）
    private var previewSurface: android.view.Surface? = null
    
    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceHelper(this)
        lightSensorManager = LightSensorManager(this)
        
        // 初始化新组件
        keepAliveManager = KeepAliveManager(this)
        batteryMonitor = BatteryMonitor(this)
        osdRenderer = OSDRenderer()
        nightVisionProcessor = NightVisionProcessor()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
        }
        return START_STICKY
    }
    
    /**
     * 开始推流
     */
    fun startStreaming() {
        // 只允许在 Idle 或 Error 状态下开始推流
        val currentState = _streamingState.value
        Log.d(TAG, "startStreaming called, currentState=$currentState")
        when (currentState) {
            is StreamingState.Streaming -> {
                Log.w(TAG, "Already streaming, ignoring start request")
                return
            }
            is StreamingState.Starting -> {
                Log.w(TAG, "Already starting, ignoring start request")
                return
            }
            is StreamingState.Stopping -> {
                Log.w(TAG, "Still stopping, waiting for completion...")
                // 等待停止完成后再重试
                serviceScope.launch {
                    // 等待状态变为 Idle
                    _streamingState.first { it is StreamingState.Idle || it is StreamingState.Error }
                    Log.d(TAG, "Stop completed, retrying startStreaming")
                    // 递归调用启动
                    startStreaming()
                }
                return
            }
            is StreamingState.Idle, is StreamingState.Error -> {
                Log.d(TAG, "State allows starting, proceeding...")
                // 允许启动
            }
        }
        
        _streamingState.value = StreamingState.Starting
        
        serviceScope.launch {
            try {
                // 先清理任何可能残留的旧资源（防止端口占用等问题）
                multiStreamServer?.stop()
                multiStreamServer = null
                
                // 重置帧计数器
                frameCount = 0
                
                // 获取配置
                val videoConfig = prefs.getVideoConfig()
                var actualVideoConfig = videoConfig  // 会被相机实际支持的分辨率更新
                val networkConfig = prefs.getNetworkConfig()
                
                // 启动前台服务
                startForeground(NOTIFICATION_ID, createNotification("正在启动..."))
                
                // 启动保活管理器
                startKeepAlive()
                
                // 启动电池监控
                startBatteryMonitoring()
                
                // 初始化相机
                cameraManager = CameraManager(this@RTSPService).also { camera ->
                    camera.startBackgroundThread()
                    
                    val cameraId = camera.getCameraId(prefs.useBackCamera)
                        ?: throw RuntimeException("No camera available")
                    
                    // 选择相机支持的最接近目标分辨率的尺寸
                    val supportedSizes = camera.getSupportedSizes(cameraId)
                    val optimalSize = camera.chooseOptimalSize(
                        supportedSizes, 
                        videoConfig.width, 
                        videoConfig.height
                    )
                    Log.d(TAG, "Target: ${videoConfig.width}x${videoConfig.height}, " +
                              "Optimal: ${optimalSize.width}x${optimalSize.height}")
                    
                    // 使用实际相机分辨率更新配置
                    actualVideoConfig = videoConfig.copy(
                        width = optimalSize.width,
                        height = optimalSize.height
                    )
                    
                    val cameraDevice = camera.openCamera(cameraId)
                    
                    // 初始化编码器并获取 Surface（使用实际分辨率）
                    val mainEncoderSurface = H264Encoder(actualVideoConfig) { buffer, info ->
                        onEncodedFrame(STREAM_LIVE, buffer, info)
                    }.also { 
                        mainEncoder = it
                    }.initialize()
                    
                    // 初始化 GPU 渲染管线（用于 OSD 水印）
                    // 相机 → 渲染管线 → 编码器 + 预览
                    val mainCameraSurface: android.view.Surface
                    if (enableOSD) {
                        mainRenderPipeline = VideoRenderPipeline(
                            this@RTSPService,
                            actualVideoConfig.width,
                            actualVideoConfig.height
                        ).also { pipeline ->
                            // 配置 OSD
                            val osdConfig = prefs.getOSDConfig()
                            pipeline.configureOsd(
                                showTimestamp = osdConfig.showTimestamp,
                                showBattery = osdConfig.showBattery,
                                showTemp = osdConfig.showTemp,
                                fontSize = osdConfig.fontSize
                            )
                        }
                        // 初始化渲染管线
                        mainCameraSurface = mainRenderPipeline!!.initialize(mainEncoderSurface)
                        mainRenderPipeline?.setRotation(prefs.rotationDegrees)
                        
                        // 设置预览 Surface（用于显示 OSD 处理后的画面）
                        previewSurface?.let { surface ->
                            if (surface.isValid) {
                                mainRenderPipeline?.setPreviewSurface(surface)
                                Log.d(TAG, "Preview surface attached to render pipeline")
                            }
                        }
                        
                        Log.d(TAG, "GPU OSD rendering enabled, rotation=${prefs.rotationDegrees}")
                    } else {
                        mainCameraSurface = mainEncoderSurface
                    }
                    
                    // 启动编码器
                    mainEncoder?.start()
                    
                    // 创建捕获会话
                    val encoderSurfaces = listOf(mainCameraSurface)
                    
                    camera.createCaptureSession(
                        cameraDevice, 
                        actualVideoConfig,
                        previewSurface = null,
                        encoderSurfaces = encoderSurfaces
                    ) { image ->
                        // 帧处理（夜视等，OSD 已由 GPU 渲染管线处理）
                        processFrame(image)
                    }
                }
                
                // 获取音频设置
                val enableAudio = prefs.enableAudio
                
                // 初始化 RTSP 服务器
                multiStreamServer = MultiStreamRTSPServer(networkConfig).also { server ->
                    // 注册单码流（带音频配置）
                    server.registerStream(MultiStreamRTSPServer.StreamConfig(
                        path = STREAM_LIVE,
                        width = actualVideoConfig.width,
                        height = actualVideoConfig.height,
                        bitrate = actualVideoConfig.bitrate,
                        frameRate = actualVideoConfig.frameRate,
                        enableAudio = enableAudio,
                        audioSampleRate = 44100,
                        audioChannels = 1
                    ))
                    
                    // 设置 SPS/PPS
                    mainEncoder?.sps?.let { sps ->
                        mainEncoder?.pps?.let { pps ->
                            server.setSpsPps(STREAM_LIVE, sps, pps)
                        }
                    }
                    
                    // 初始化音频编码器（如果启用）
                    if (enableAudio) {
                        audioEncoder = AACEncoder(
                            sampleRate = 44100,
                            channelCount = 1,
                            bitrate = 64000
                        ) { buffer, info ->
                            // 发送音频帧到 RTSP 服务器
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(data, 0, info.size)
                            val timestamp = info.presentationTimeUs / 1000  // us -> ms
                            multiStreamServer?.sendAudioFrame(STREAM_LIVE, data, timestamp)
                        }
                        
                        // 设置 ASC 可用回调（在 OUTPUT_FORMAT_CHANGED 后异步触发）
                        audioEncoder?.setOnAscAvailable { asc ->
                            server.setAudioConfig(STREAM_LIVE, asc)
                            Log.i(TAG, "Audio config set to RTSP server (ASC: ${asc.size} bytes)")
                        }
                        
                        if (audioEncoder?.initialize() == true) {
                            audioEncoder?.start()
                            Log.i(TAG, "Audio encoder started")
                        } else {
                            Log.w(TAG, "Audio encoder initialization failed, continuing without audio")
                            audioEncoder = null
                        }
                    }
                    
                    server.onClientConnected = { _, totalCount ->
                        updateNotification("$totalCount 客户端已连接")
                        updateStreamingState(totalCount)
                    }
                    
                    server.onClientDisconnected = { _, totalCount ->
                        updateNotification("$totalCount 客户端已连接")
                        updateStreamingState(totalCount)
                    }
                    
                    // 客户端开始播放时请求关键帧（确保新客户端能解码）
                    server.onClientStartedPlaying = { _ ->
                        Log.d(TAG, "Client started playing, requesting key frame")
                        mainEncoder?.requestKeyFrame()
                    }
                    
                    if (!server.start()) {
                        throw RuntimeException("Failed to start RTSP server")
                    }
                }
                
                // 启动光线传感器（如果是自动闪光灯模式）
                currentFlashMode = prefs.flashMode
                if (currentFlashMode == FlashMode.AUTO) {
                    startAutoFlash()
                } else if (currentFlashMode == FlashMode.ON) {
                    cameraManager?.setFlash(true)
                }
                
                // 构建 RTSP URL
                val ip = NetworkUtils.getWifiIpAddress(this@RTSPService)
                    ?: NetworkUtils.getDeviceIpAddresses(
                        this@RTSPService,
                        networkConfig.ipv4Only,
                        networkConfig.lanOnly
                    ).firstOrNull() ?: "0.0.0.0"
                
                val liveUrl = NetworkUtils.buildRtspUrl(ip, networkConfig.port, STREAM_LIVE)
                
                _streamingState.value = StreamingState.Streaming(liveUrl)
                updateNotification("推流地址: $liveUrl")
                
                Log.i(TAG, "Streaming started: $liveUrl")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                _streamingState.value = StreamingState.Error(e.message ?: "Unknown error")
                stopStreaming()
            }
        }
    }
    
    /**
     * 处理每一帧（OSD叠加、夜视增强）
     */
    @Suppress("UNUSED_PARAMETER")
    private fun processFrame(image: Image) {
        // 如果两个功能都没启用，跳过处理
        if (!enableOSD && !enableNightVision) return
        
        // 夜视增强处理
        if (enableNightVision && nightVisionProcessor?.enabled == true) {
            // 夜视处理会在编码前对YUV数据进行处理
            // 这里需要获取YUV数据并处理
            // 由于Camera2使用Surface输入编码器，这里仅作示意
        }
        
        // OSD水印由OSDRenderer在需要时渲染
    }
    
    /**
     * 停止推流
     */
    fun stopStreaming() {
        _streamingState.value = StreamingState.Stopping
        
        serviceScope.launch {
            // 停止光线传感器
            lightSensorManager?.stop()
            
            // 停止电池监控
            batteryMonitor?.stopMonitoring()
            
            // 停止保活管理器
            keepAliveManager?.stop()
            
            // 停止RTSP服务器
            multiStreamServer?.stop()
            multiStreamServer = null
            
            // 先关闭相机（避免继续往 Surface 写数据）
            cameraManager?.closeCamera()
            cameraManager?.stopBackgroundThread()
            cameraManager = null
            
            // 停止渲染管线
            mainRenderPipeline?.stop()
            mainRenderPipeline = null
            
            // 停止视频编码器
            mainEncoder?.release()
            mainEncoder = null
            
            // 停止音频编码器
            audioEncoder?.release()
            audioEncoder = null
            
            _streamingState.value = StreamingState.Idle
            
            // 保持前台服务运行，但更新通知为待机状态（防止进程被系统杀死）
            val notification = createNotification("待机中 - 点击启动推流")
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
            
            Log.i(TAG, "Streaming stopped")
        }
    }
    
    /**
     * 编码帧回调
     */
    private var frameCount = 0
    private fun onEncodedFrame(streamPath: String, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.get(data)
        
        frameCount++
        if (frameCount <= 5 || frameCount % 100 == 0) {
            Log.d(TAG, "onEncodedFrame[$streamPath]: frame=$frameCount, size=${info.size}, flags=${info.flags}")
        }
        
        // 更新 SPS/PPS
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            Log.d(TAG, "Got codec config frame for $streamPath")
            mainEncoder?.sps?.let { sps ->
                mainEncoder?.pps?.let { pps ->
                    Log.d(TAG, "Setting SPS/PPS: sps=${sps.size}, pps=${pps.size}")
                    multiStreamServer?.setSpsPps(STREAM_LIVE, sps, pps)
                }
            } ?: Log.w(TAG, "SPS/PPS is null!")
            return
        }
        
        // 发送到 RTSP 服务器
        val clientCount = multiStreamServer?.getClientCount(streamPath) ?: 0
        if (frameCount <= 5) {
            Log.d(TAG, "Sending frame to RTSP: path=$streamPath, clients=$clientCount")
        }
        multiStreamServer?.sendVideoFrame(streamPath, data, info.presentationTimeUs / 1000)
    }
    
    /**
     * 启动保活管理器
     */
    private fun startKeepAlive() {
        keepAliveManager?.apply {
            onKeepAliveCheck = {
                // 检查服务是否正常运行
                _streamingState.value is StreamingState.Streaming
            }
            onServiceDied = {
                // 服务异常，尝试重启
                Log.w(TAG, "Service check failed, attempting restart")
                startStreaming()
            }
            start()
        }
    }
    
    /**
     * 启动电池监控
     */
    private fun startBatteryMonitoring() {
        batteryMonitor?.startMonitoring { info ->
            // 更新OSD渲染器的电池信息
            osdRenderer?.apply {
                batteryLevel = info.level
                batteryTemperature = info.temperature
                isCharging = info.isCharging
            }
            
            // 更新渲染管道的电池信息
            mainRenderPipeline?.updateBatteryInfo(info.level, info.temperature, info.isCharging)
            
            // 更新带宽信息
            multiStreamServer?.let { server ->
                mainRenderPipeline?.updateBandwidth(
                    server.currentUploadBandwidth,
                    server.currentDownloadBandwidth
                )
            }
            
            // 低电量警告
            if (info.level <= 10 && !info.isCharging) {
                updateNotification("⚠️ 电量低: ${info.level}%")
            }
        }
    }
    
    /**
     * 设置闪光灯模式
     */
    fun setFlashMode(mode: FlashMode) {
        currentFlashMode = mode
        prefs.flashMode = mode
        
        // 更新 OSD 显示的闪光灯模式
        mainRenderPipeline?.updateFlashMode(mode.name)
        
        when (mode) {
            FlashMode.OFF -> {
                lightSensorManager?.stop()
                cameraManager?.setFlash(false)
            }
            FlashMode.ON -> {
                lightSensorManager?.stop()
                cameraManager?.setFlash(true)
            }
            FlashMode.AUTO -> {
                startAutoFlash()
            }
        }
    }
    
    /**
     * 设置预览 Surface（用于显示 OSD 处理后的推流画面）
     */
    fun setPreviewSurface(surface: android.view.Surface?) {
        previewSurface = surface
        // 如果渲染管线已初始化，直接设置预览
        mainRenderPipeline?.setPreviewSurface(surface)
    }
    
    /**
     * 设置夜视增强（通过 GPU 渲染管线实现）
     */
    fun setNightVisionEnabled(enabled: Boolean) {
        enableNightVision = enabled
        nightVisionProcessor?.enabled = enabled
        // 通过 GPU 渲染管线实现夜视增强
        mainRenderPipeline?.setNightVision(enabled)
        // 更新 OSD 显示
        mainRenderPipeline?.updateNightVision(enabled)
    }
    
    /**
     * 设置画面缩放（用于手动调整宽高比）
     * @param scaleX 水平缩放 (1.0 = 正常)
     * @param scaleY 垂直缩放 (1.0 = 正常)
     */
    fun setScale(scaleX: Float, scaleY: Float) {
        mainRenderPipeline?.setScale(scaleX, scaleY)
    }
    
    /**
     * 设置OSD水印
     */
    fun setOSDEnabled(enabled: Boolean) {
        enableOSD = enabled
    }
    
    /**
     * 设置画面旋转 (实时生效)
     */
    fun setRotation(degrees: Int) {
        mainRenderPipeline?.setRotation(degrees)
    }
    
    /**
     * 配置OSD显示内容
     */
    fun configureOSD(
        showTimestamp: Boolean = true,
        showBattery: Boolean = true,
        showTemp: Boolean = true,
        customText: String? = null
    ) {
        osdRenderer?.apply {
            this.showTimestamp = showTimestamp
            this.showBatteryLevel = showBattery
            this.showBatteryTemp = showTemp
            this.showCustomText = customText
        }
    }
    
    /**
     * 获取OSD渲染器（用于外部配置）
     */
    fun getOSDRenderer(): OSDRenderer? = osdRenderer
    
    /**
     * 获取夜视处理器（用于外部配置）
     */
    fun getNightVisionProcessor(): NightVisionProcessor? = nightVisionProcessor
    
    /**
     * 获取当前电池信息
     */
    fun getBatteryInfo(): BatteryMonitor.BatteryInfo {
        return batteryMonitor?.getBatteryInfo() 
            ?: BatteryMonitor.BatteryInfo(100, 25f, 0, false, BatteryMonitor.PlugType.NONE)
    }
    
    /**
     * 获取推流 URL
     */
    fun getStreamUrls(): Map<String, String> {
        val networkConfig = prefs.getNetworkConfig()
        val ip = NetworkUtils.getWifiIpAddress(this)
            ?: NetworkUtils.getDeviceIpAddresses(this, networkConfig.ipv4Only, networkConfig.lanOnly)
                .firstOrNull() ?: "0.0.0.0"
        
        return mapOf(
            "live" to NetworkUtils.buildRtspUrl(ip, networkConfig.port, STREAM_LIVE)
        )
    }
    
    /**
     * 启动自动闪光灯
     */
    private fun startAutoFlash() {
        lightSensorManager?.apply {
            setOnLightLevelChangedListener { lux ->
                val threshold = prefs.autoFlashThreshold
                val shouldEnable = lux < threshold
                cameraManager?.setFlash(shouldEnable)
                
                // 更新 OSD 显示的光照度
                mainRenderPipeline?.updateLightLevel(lux)
                
                // 根据环境光自动启用夜视增强
                if (enableNightVision) {
                    nightVisionProcessor?.autoAdjust = true
                }
            }
            start()
        }
    }
    
    /**
     * 更新推流状态
     */
    private fun updateStreamingState(clientCount: Int) {
        val currentState = _streamingState.value
        if (currentState is StreamingState.Streaming) {
            _streamingState.value = currentState.copy(clientCount = clientCount)
        }
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, RTSPService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // 添加电池信息到通知
        val batteryInfo = batteryMonitor?.getBatteryInfo()
        val batteryText = if (batteryInfo != null) {
            "\n🔋 ${batteryInfo.level}% ${if (batteryInfo.isCharging) "⚡" else ""} | ${batteryInfo.temperature}°C"
        } else ""
        
        return NotificationCompat.Builder(this, RTSPCameraApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content + batteryText))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 同步清理所有资源（不能依赖协程，因为马上要 cancel）
        try {
            // 停止光线传感器
            lightSensorManager?.stop()
            lightSensorManager = null
            
            // 停止电池监控
            batteryMonitor?.stopMonitoring()
            batteryMonitor = null
            
            // 停止保活管理器（会注销 BroadcastReceiver）
            keepAliveManager?.stop()
            keepAliveManager = null
            
            // 停止RTSP服务器
            multiStreamServer?.stop()
            multiStreamServer = null
            
            // 先关闭相机（避免继续往 Surface 写数据）
            cameraManager?.closeCamera()
            cameraManager?.stopBackgroundThread()
            cameraManager = null
            
            // 停止渲染管线
            mainRenderPipeline?.stop()
            mainRenderPipeline = null
            
            // 停止视频编码器
            mainEncoder?.release()
            mainEncoder = null
            
            // 停止音频编码器
            audioEncoder?.release()
            audioEncoder = null
            
            Log.i(TAG, "RTSPService destroyed, all resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup in onDestroy", e)
        }
        
        // 最后取消协程
        serviceScope.cancel()
    }
}
