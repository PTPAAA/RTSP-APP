package com.rtsp.camera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.rtsp.camera.model.VideoConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2相机管理器
 * 负责相机的打开、预览、帧捕获
 */
class CameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CameraManager"
    }
    
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    
    // 保存 Surface 引用以便在 setFlash 等方法中重新使用
    private var savedEncoderSurfaces: List<Surface> = emptyList()
    private var savedPreviewSurface: Surface? = null
    
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var frameCallback: ((Image) -> Unit)? = null
    private var isFlashOn = false
    
    private val cameraManager: android.hardware.camera2.CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
    }
    
    /**
     * 启动后台线程
     */
    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }
    
    /**
     * 停止后台线程
     */
    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread interrupted", e)
        }
    }
    
    /**
     * 获取相机ID
     * @param useBackCamera 是否使用后置摄像头
     */
    fun getCameraId(useBackCamera: Boolean = true): String? {
        val targetLensFacing = if (useBackCamera) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        
        return cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == targetLensFacing
        }
    }
    
    /**
     * 检查相机是否支持闪光灯
     */
    fun hasFlash(cameraId: String): Boolean {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }
    
    /**
     * 获取支持的分辨率列表
     */
    fun getSupportedSizes(cameraId: String): List<Size> {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        return map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
    }
    
    /**
     * 选择最接近目标分辨率的尺寸（优先匹配宽高比）
     */
    fun chooseOptimalSize(supportedSizes: List<Size>, targetWidth: Int, targetHeight: Int): Size {
        if (supportedSizes.isEmpty()) return Size(targetWidth, targetHeight)
        
        val targetAspect = targetWidth.toFloat() / targetHeight
        val aspectTolerance = 0.1f  // 10% 宽高比容差
        
        // 首先筛选宽高比接近的尺寸
        val matchingAspect = supportedSizes.filter { size ->
            val aspect = size.width.toFloat() / size.height
            Math.abs(aspect - targetAspect) < aspectTolerance
        }
        
        // 如果有匹配宽高比的，选最接近目标分辨率的
        if (matchingAspect.isNotEmpty()) {
            return matchingAspect.minByOrNull { size ->
                Math.abs(size.width - targetWidth) + Math.abs(size.height - targetHeight)
            }!!
        }
        
        // 回退：选择总像素数最接近的
        return supportedSizes.minByOrNull { size ->
            Math.abs(size.width * size.height - targetWidth * targetHeight)
        } ?: Size(targetWidth, targetHeight)
    }
    
    /**
     * 打开相机
     */
    @SuppressLint("MissingPermission")
    suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    continuation.resume(camera)
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    continuation.resumeWithException(
                        RuntimeException("Camera open error: $error")
                    )
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * 创建捕获会话
     */
    suspend fun createCaptureSession(
        camera: CameraDevice,
        config: VideoConfig,
        previewSurface: Surface? = null,
        encoderSurfaces: List<Surface> = emptyList(),
        onFrame: (Image) -> Unit
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        frameCallback = onFrame
        
        // 创建ImageReader
        imageReader = ImageReader.newInstance(
            config.width,
            config.height,
            ImageFormat.YUV_420_888,
            3 // 缓冲区数量
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    frameCallback?.invoke(image)
                    image.close()
                }
            }, backgroundHandler)
        }
        
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces.add(it) }
        surfaces.addAll(encoderSurfaces)
        surfaces.add(imageReader!!.surface)
        
        // 保存 Surface 引用
        savedPreviewSurface = previewSurface
        savedEncoderSurfaces = encoderSurfaces
        
        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    
                    // 创建预览请求（使用RECORD模板以优化编码质量）
                    val requestBuilder = camera.createCaptureRequest(
                        if (encoderSurfaces.isNotEmpty()) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                    ).apply {
                        previewSurface?.let { addTarget(it) }
                        encoderSurfaces.forEach { addTarget(it) }
                        addTarget(imageReader!!.surface)
                        
                        // 自动对焦
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        // 自动曝光
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                    
                    session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                    continuation.resume(session)
                }
                
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    continuation.resumeWithException(
                        RuntimeException("Capture session configuration failed")
                    )
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
    
    /**
     * 设置闪光灯状态
     */
    fun setFlash(enabled: Boolean) {
        isFlashOn = enabled
        val session = captureSession
        val camera = cameraDevice
        
        if (session == null || camera == null) {
            Log.w(TAG, "setFlash: session or camera is null, cannot set flash")
            return
        }
        
        Log.d(TAG, "setFlash: enabled=$enabled, previewSurface=${savedPreviewSurface != null}, " +
                   "encoderSurfaces=${savedEncoderSurfaces.size}")
        
        try {
            // 使用正确的模板：如果有编码器 Surface，使用 TEMPLATE_RECORD
            val template = if (savedEncoderSurfaces.isNotEmpty()) {
                CameraDevice.TEMPLATE_RECORD
            } else {
                CameraDevice.TEMPLATE_PREVIEW
            }
            
            val requestBuilder = camera.createCaptureRequest(template).apply {
                // 添加所有保存的 Surface
                savedPreviewSurface?.let { addTarget(it) }
                savedEncoderSurfaces.forEach { addTarget(it) }
                imageReader?.surface?.let { addTarget(it) }
                
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                
                if (enabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
            
            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
            Log.d(TAG, "setFlash: request submitted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set flash", e)
        }
    }
    
    /**
     * 关闭相机
     */
    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            frameCallback = null
            savedPreviewSurface = null
            savedEncoderSurfaces = emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
    
    /**
     * 相机是否已打开
     */
    fun isOpened(): Boolean = cameraDevice != null
    
    /**
     * 仅启动预览（不需要编码器）
     * 用于 Activity 在未推流时独立预览
     */
    @SuppressLint("MissingPermission")
    suspend fun startPreviewOnly(
        cameraId: String,
        previewSurface: Surface
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        startBackgroundThread()
        
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    savedPreviewSurface = previewSurface
                    
                    val surfaces = listOf(previewSurface)
                    camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }
                            
                            session.setRepeatingRequest(requestBuilder.build(), null, backgroundHandler)
                            continuation.resume(session)
                        }
                        
                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            continuation.resumeWithException(
                                RuntimeException("Preview session configuration failed")
                            )
                        }
                    }, backgroundHandler)
                }
                
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }
                
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    continuation.resumeWithException(
                        RuntimeException("Camera open error: $error")
                    )
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}
