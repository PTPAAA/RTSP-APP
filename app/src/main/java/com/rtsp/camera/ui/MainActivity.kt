package com.rtsp.camera.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rtsp.camera.R
import com.rtsp.camera.camera.CameraManager
import com.rtsp.camera.databinding.ActivityMainBinding
import com.rtsp.camera.model.FlashMode
import com.rtsp.camera.model.StreamingState
import com.rtsp.camera.service.RTSPService
import com.rtsp.camera.util.PreferenceHelper
import com.rtsp.camera.lighting.ScreenFlashManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 全屏相机风格界面
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferenceHelper

    private var rtspService: RTSPService? = null
    private var serviceBound = false
    private var pendingStartStreaming = false
    
    // 屏幕补光管理器
    private var screenFlashManager: ScreenFlashManager? = null
    private var saverFlashManager: ScreenFlashManager? = null  // 挂机模式用
    private var lowLightEnabled = false

    private val streamingPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startStreamingService()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RTSPService.LocalBinder
            rtspService = binder.getService()
            serviceBound = true

            // 检查是否有待启动的推流请求
            if (pendingStartStreaming) {
                pendingStartStreaming = false
                rtspService?.startStreaming()
            }
            observeStreamingState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            rtspService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 全屏模式
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PreferenceHelper(this)

        setupUI()
        setupPreview()
    }

    private fun setupUI() {
        // 1. 闪光灯按钮
        updateFlashUI(prefs.flashMode)
        binding.btnFlash.setOnClickListener {
            val newMode = when (prefs.flashMode) {
                FlashMode.OFF -> FlashMode.ON
                FlashMode.ON -> FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.OFF
            }
            prefs.flashMode = newMode
            rtspService?.setFlashMode(newMode)
            updateFlashUI(newMode)
        }

        // 2. 低光增强按钮 (原夜视按钮)
        updateNightVisionUI(prefs.enableNightVision)
        binding.btnNightVision.setOnClickListener {
            lowLightEnabled = !lowLightEnabled
            prefs.enableNightVision = lowLightEnabled
            
            // 启用 GPU 低光增强
            rtspService?.setLowLightEnhancement(lowLightEnabled)
            
            // 根据摄像头类型选择补光方式
            val useBackCamera = prefs.useBackCamera
            if (lowLightEnabled) {
                if (useBackCamera) {
                    // 后置摄像头：使用闪光灯
                    rtspService?.setFlashMode(FlashMode.ON)
                    screenFlashManager?.disable()
                } else {
                    // 前置摄像头：使用屏幕补光（半透明模式，不遮挡时钟）
                    screenFlashManager?.stealthMode = true
                    screenFlashManager?.enable()
                }
            } else {
                // 关闭补光
                if (useBackCamera) {
                    rtspService?.setFlashMode(FlashMode.OFF)
                } else {
                    screenFlashManager?.disable()
                }
            }
            
            updateNightVisionUI(lowLightEnabled)
            val modeText = if (useBackCamera) "闪光灯" else "屏幕补光"
            Toast.makeText(this, if (lowLightEnabled) "低光增强已开启 ($modeText)" else "低光增强已关闭", Toast.LENGTH_SHORT).show()
        }

        // 3. 旋转按钮
        binding.btnRotate.setOnClickListener {
            val newDegree = (prefs.rotationDegrees + 90) % 360
            prefs.rotationDegrees = newDegree
            rtspService?.setRotation(newDegree)
            Toast.makeText(this, "旋转 $newDegree°", Toast.LENGTH_SHORT).show()
        }

        // 4. OSD 按钮
        updateOsdUI(prefs.enableOSD)
        binding.btnOsd.setOnClickListener {
            val newState = !prefs.enableOSD
            prefs.enableOSD = newState
            rtspService?.setOSDEnabled(newState)
            updateOsdUI(newState)
        }

        // 5. 开始/停止按钮
        binding.btnStartStop.setOnClickListener {
            val currentState = rtspService?.streamingState?.value
            Log.d("MainActivity", "Button clicked, rtspService=${rtspService != null}, serviceBound=$serviceBound, state=$currentState")
            if (currentState is StreamingState.Streaming) {
                stopStreamingService()
            } else {
                checkPermissionsAndStart()
            }
        }

        // 6. 设置按钮
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 7. RTSP URL 点击复制
        binding.rtspUrlText.setOnClickListener {
            val url = binding.rtspUrlText.text.toString()
            if (url.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("RTSP URL", url))
                Toast.makeText(this, "URL 已复制", Toast.LENGTH_SHORT).show()
            }
        }

        // 8. OLED 保护模式 (长按预览区域)
        binding.previewView.setOnLongClickListener {
            enableOledSaverMode(true)
            true
        }
        
        // 挂机模式按钮
        binding.btnScreenSaver.setOnClickListener {
            enableOledSaverMode(true)
        }

        binding.oledSaverOverlay.setOnClickListener {
            val now = System.currentTimeMillis()
            // 检查是否超时，超时则重置计数
            if (now - lastSaverClickTime > SAVER_EXIT_CLICK_TIMEOUT) {
                saverExitClickCount = 0
            }
            lastSaverClickTime = now
            saverExitClickCount++
            
            if (saverExitClickCount >= SAVER_EXIT_CLICK_REQUIRED) {
                saverExitClickCount = 0
                enableOledSaverMode(false)
            }
        }
        
        // 9. 初始化屏幕补光管理器
        screenFlashManager = ScreenFlashManager(window, binding.screenFlashOverlay).apply {
            intervalMs = 5000  // 每 5 秒补光一次
            flashDurationMs = 500  // 补光持续 500ms
            autoMode = false  // 手动控制
            stealthMode = true  // 半透明模式
        }
        
        // 10. 初始化挂机模式补光管理器（使用 saverFlashOverlay，位于时钟下方）
        saverFlashManager = ScreenFlashManager(window, binding.saverFlashOverlay).apply {
            intervalMs = 5000  // 每 5 秒补光一次
            flashDurationMs = 800  // 补光持续 800ms（更长以便捕捉画面）
            autoMode = false  // 手动控制
            stealthMode = true  // 半透明模式，不遮挡时钟
        }
    }

    private fun updateFlashUI(mode: FlashMode) {
        val color = when (mode) {
            FlashMode.OFF -> ContextCompat.getColor(this, R.color.on_surface_variant)
            FlashMode.ON -> ContextCompat.getColor(this, R.color.neon_yellow)
            FlashMode.AUTO -> ContextCompat.getColor(this, R.color.neon_green)
        }
        binding.btnFlash.setColorFilter(color)
        binding.btnFlash.setImageResource(
            if (mode == FlashMode.OFF) R.drawable.ic_flash_off else R.drawable.ic_flash_on
        )
    }

    private fun updateNightVisionUI(enabled: Boolean) {
        val color = if (enabled) ContextCompat.getColor(this, R.color.neon_green) 
                    else ContextCompat.getColor(this, R.color.on_surface_variant)
        binding.btnNightVision.setColorFilter(color)
    }

    private fun updateOsdUI(enabled: Boolean) {
        val color = if (enabled) ContextCompat.getColor(this, R.color.neon_green) 
                    else ContextCompat.getColor(this, R.color.on_surface_variant)
        binding.btnOsd.setColorFilter(color)
    }

    private fun updateStreamingUI(isStreaming: Boolean) {
        if (isStreaming) {
            binding.btnStartStop.setIconResource(R.drawable.ic_stop)
            binding.btnStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.neon_red)
            )
            binding.recordButtonRing.background = ContextCompat.getDrawable(this, R.drawable.record_button_ring_active)
            binding.rtspUrlText.visibility = View.VISIBLE
            
            // 推流时显示预览容器，隐藏配置信息
            binding.previewContainer.visibility = View.VISIBLE
            binding.configInfoLayout.visibility = View.GONE
            
            // 调整预览 SurfaceView 尺寸以保持视频宽高比
            adjustPreviewAspectRatio()
            
            // 设置预览 Surface 到服务
            binding.previewView.holder.surface?.let { surface ->
                if (surface.isValid) {
                    rtspService?.setPreviewSurface(surface)
                }
            }
            
            // 更新状态
            binding.statusText.text = "推流中"
            (binding.statusDot.background as? GradientDrawable)?.setColor(Color.RED)
        } else {
            binding.btnStartStop.setIconResource(R.drawable.ic_play)
            binding.btnStartStop.setBackgroundTintList(
                ContextCompat.getColorStateList(this, R.color.neon_green)
            )
            binding.recordButtonRing.background = ContextCompat.getDrawable(this, R.drawable.record_button_ring)
            binding.rtspUrlText.visibility = View.GONE
            
            // 停止推流时隐藏预览容器，显示配置信息
            binding.previewContainer.visibility = View.GONE
            binding.configInfoLayout.visibility = View.VISIBLE
            updateConfigInfo()
            
            // 移除预览 Surface
            rtspService?.setPreviewSurface(null)
            
            // 更新状态
            binding.statusText.text = getString(R.string.status_idle)
            (binding.statusDot.background as? GradientDrawable)?.setColor(
                ContextCompat.getColor(this, R.color.neon_green)
            )
        }
    }
    
    private fun adjustPreviewAspectRatio() {
        val videoConfig = prefs.getVideoConfig()
        val videoWidth = videoConfig.width
        val videoHeight = videoConfig.height
        
        binding.previewContainer.post {
            val containerWidth = binding.previewContainer.width
            val containerHeight = binding.previewContainer.height
            
            if (containerWidth == 0 || containerHeight == 0) return@post
            
            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()
            
            val (targetWidth, targetHeight) = if (videoRatio > containerRatio) {
                // 视频更宽，以容器宽度为准
                containerWidth to (containerWidth / videoRatio).toInt()
            } else {
                // 视频更高，以容器高度为准
                (containerHeight * videoRatio).toInt() to containerHeight
            }
            
            val params = binding.previewView.layoutParams as FrameLayout.LayoutParams
            params.width = targetWidth
            params.height = targetHeight
            params.gravity = android.view.Gravity.CENTER
            binding.previewView.layoutParams = params
        }
    }
    
    private fun updateConfigInfo() {
        val videoConfig = prefs.getVideoConfig()
        val networkConfig = prefs.getNetworkConfig()
        val configText = buildString {
            appendLine("${videoConfig.width} x ${videoConfig.height}")
            appendLine("${videoConfig.frameRate} fps")
            appendLine("${videoConfig.bitrate / 1000} Mbps")
            appendLine("H.264")
            appendLine()
            append("端口: ${networkConfig.port}")
        }
        binding.configInfoText.text = configText
    }

    private var oledSaverHandler: android.os.Handler? = null
    private var oledSaverRunnable: Runnable? = null
    private val random = java.util.Random()
    
    // 重力传感器
    private var sensorManager: android.hardware.SensorManager? = null
    private var gravitySensor: android.hardware.Sensor? = null
    private var gravitySensorListener: android.hardware.SensorEventListener? = null
    private var currentSaverRotation = 0f
    
    // 挂机模式退出计数器（需要连续点击 5 次）
    private var saverExitClickCount = 0
    private var lastSaverClickTime = 0L
    private val SAVER_EXIT_CLICK_TIMEOUT = 2000L  // 2 秒内完成 5 次点击
    private val SAVER_EXIT_CLICK_REQUIRED = 5
    
    private fun enableOledSaverMode(enable: Boolean) {
        binding.oledSaverOverlay.isVisible = enable
        val windowController = WindowCompat.getInsetsController(window, window.decorView)
        
        if (enable) {
            windowController.hide(WindowInsetsCompat.Type.systemBars())
            windowController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            // 强制横屏显示时钟容器
            binding.saverClockContainer.rotation = 90f
            
            // 启动时钟更新
            startOledSaverClock()
            
            // 启动重力传感器
            startGravitySensor()
            
            // 挂机模式下启用屏幕补光（如果低光增强开启且是前置摄像头）
            if (lowLightEnabled && !prefs.useBackCamera) {
                saverFlashManager?.enable()
            }
            
            Toast.makeText(this, "点击屏幕退出", Toast.LENGTH_SHORT).show()
        } else {
            windowController.show(WindowInsetsCompat.Type.systemBars())
            
            // 恢复旋转
            binding.saverClockContainer.rotation = 0f
            
            // 停止时钟更新
            stopOledSaverClock()
            
            // 停止重力传感器
            stopGravitySensor()
            
            // 停止挂机模式补光
            saverFlashManager?.disable()
        }
    }
    
    private fun startGravitySensor() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        gravitySensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        
        gravitySensorListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                event?.let {
                    val x = it.values[0]
                    val y = it.values[1]
                    
                    // 根据重力方向计算旋转角度
                    // x > 0: 左倾斜, x < 0: 右倾斜
                    // y > 0: 向下, y < 0: 向上
                    val targetRotation = when {
                        y > 5 -> 90f    // 正常竖屏向上 -> 横屏
                        y < -5 -> 270f  // 倒立竖屏 -> 横屏反向
                        x > 5 -> 0f     // 左横屏
                        x < -5 -> 180f  // 右横屏
                        else -> currentSaverRotation // 保持当前角度
                    }
                    
                    // 平滑旋转
                    if (targetRotation != currentSaverRotation) {
                        currentSaverRotation = targetRotation
                        binding.saverClockContainer.animate()
                            .rotation(targetRotation)
                            .setDuration(300)
                            .start()
                    }
                }
            }
            
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
        }
        
        gravitySensor?.let {
            sensorManager?.registerListener(
                gravitySensorListener,
                it,
                android.hardware.SensorManager.SENSOR_DELAY_UI
            )
        }
    }
    
    private fun stopGravitySensor() {
        gravitySensorListener?.let {
            sensorManager?.unregisterListener(it)
        }
        gravitySensorListener = null
        gravitySensor = null
        sensorManager = null
    }
    
    private fun startOledSaverClock() {
        oledSaverHandler = android.os.Handler(mainLooper)
        val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd EEEE", java.util.Locale.getDefault())
        
        oledSaverRunnable = object : Runnable {
            override fun run() {
                // 更新时间
                val now = java.util.Date()
                binding.saverTimeText.text = timeFormat.format(now)
                binding.saverDateText.text = dateFormat.format(now)
                
                // 每 30 秒随机移动位置 (防烧屏)
                val seconds = java.util.Calendar.getInstance().get(java.util.Calendar.SECOND)
                if (seconds == 0 || seconds == 30) {
                    moveClockRandomly()
                }
                
                oledSaverHandler?.postDelayed(this, 1000)
            }
        }
        
        oledSaverHandler?.post(oledSaverRunnable!!)
    }
    
    private fun stopOledSaverClock() {
        oledSaverRunnable?.let { oledSaverHandler?.removeCallbacks(it) }
        oledSaverHandler = null
        oledSaverRunnable = null
    }
    
    private fun moveClockRandomly() {
        val container = binding.saverClockContainer
        val parent = binding.oledSaverOverlay
        
        // 计算可移动范围 (考虑旋转后的尺寸)
        val maxX = (parent.width - container.height).coerceAtLeast(0)  // 旋转后宽高互换
        val maxY = (parent.height - container.width).coerceAtLeast(0)
        
        if (maxX > 0 && maxY > 0) {
            val newX = random.nextInt(maxX).toFloat()
            val newY = random.nextInt(maxY).toFloat()
            
            // 平滑动画移动
            container.animate()
                .translationX(newX - parent.width / 2f + container.height / 2f)
                .translationY(newY - parent.height / 2f + container.width / 2f)
                .setDuration(2000)
                .start()
        }
    }

    private fun setupPreview() {
        // 设置预览 Surface 回调
        binding.previewView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                // Surface 创建后，如果正在推流则设置预览
                val isStreaming = rtspService?.streamingState?.value is StreamingState.Streaming
                if (isStreaming) {
                    rtspService?.setPreviewSurface(holder.surface)
                }
            }
            
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                // Surface 变化后，如果正在推流则更新预览
                val isStreaming = rtspService?.streamingState?.value is StreamingState.Streaming
                if (isStreaming) {
                    rtspService?.setPreviewSurface(holder.surface)
                }
            }
            
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                rtspService?.setPreviewSurface(null)
            }
        })
        
        // 初始化配置信息显示
        updateConfigInfo()
    }

    private fun observeStreamingState() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                rtspService?.streamingState?.collectLatest { state ->
                    when (state) {
                        is StreamingState.Idle -> {
                            updateStreamingUI(false)
                            binding.clientCountText.text = "0 客户端"
                            binding.btnStartStop.isEnabled = true
                        }
                        is StreamingState.Starting -> {
                            binding.statusText.text = "启动中..."
                            binding.btnStartStop.isEnabled = false
                        }
                        is StreamingState.Streaming -> {
                            updateStreamingUI(true)
                            binding.btnStartStop.isEnabled = true
                            binding.rtspUrlText.text = state.rtspUrl
                            binding.clientCountText.text = "${state.clientCount} 客户端"
                        }
                        is StreamingState.Stopping -> {
                            binding.statusText.text = "停止中..."
                            binding.btnStartStop.isEnabled = false
                        }
                        is StreamingState.Error -> {
                            updateStreamingUI(false)
                            binding.btnStartStop.isEnabled = true
                            Toast.makeText(this@MainActivity, "错误: ${state.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) {
            startStreamingService()
        } else {
            streamingPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startStreamingService() {
        // 如果 Service 已绑定且可用，直接启动推流
        val service = rtspService
        if (service != null) {
            service.startStreaming()
        } else {
            // Service 未就绪，设置标志位，等待 onServiceConnected 回调
            pendingStartStreaming = true
            
            // 确保 Service 启动并绑定
            val intent = Intent(this, RTSPService::class.java)
            startForegroundService(intent)
            
            // 只有在未绑定时才调用 bindService
            if (!serviceBound) {
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    private fun stopStreamingService() {
        rtspService?.stopStreaming()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RTSPService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        screenFlashManager?.release()
        screenFlashManager = null
        saverFlashManager?.release()
        saverFlashManager = null
    }
}
