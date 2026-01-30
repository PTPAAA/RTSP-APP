package com.rtsp.camera.renderer

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.*
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GPU加速的相机渲染器
 * 使用 OpenGL ES 将相机帧和 OSD 水印合成后输出到编码器
 */
class GLCameraRenderer(
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "GLCameraRenderer"
        
        // 顶点着色器
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            attribute vec2 aOsdTexCoord;
            uniform float uScaleX;  // 水平缩放 (1.0 = 正常)
            uniform float uScaleY;  // 垂直缩放 (1.0 = 正常)
            varying vec2 vTexCoord;
            varying vec2 vOsdTexCoord;
            void main() {
                // 应用画面缩放
                vec4 scaledPosition = aPosition;
                scaledPosition.x = aPosition.x * uScaleX;
                scaledPosition.y = aPosition.y * uScaleY;
                gl_Position = scaledPosition;
                vTexCoord = aTexCoord;
                vOsdTexCoord = aOsdTexCoord;
            }
        """
        
        // 相机纹理片段着色器 (外部纹理 OES)
        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            varying vec2 vOsdTexCoord;
            uniform samplerExternalOES uCameraTexture;
            uniform sampler2D uOsdTexture;
            uniform float uOsdEnabled;
            uniform float uBrightness; // 夜视增强：亮度 (1.0 = 正常)
            uniform float uContrast;   // 夜视增强：对比度 (1.0 = 正常)
            void main() {
                vec4 cameraColor = texture2D(uCameraTexture, vTexCoord);
                
                // 应用夜视增强 (亮度和对比度调整)
                vec3 color = cameraColor.rgb;
                color = color * uBrightness;                    // 亮度
                color = (color - 0.5) * uContrast + 0.5;       // 对比度
                color = clamp(color, 0.0, 1.0);                // 限制范围
                cameraColor = vec4(color, cameraColor.a);
                
                vec4 osdColor = texture2D(uOsdTexture, vOsdTexCoord);
                // 使用 alpha 混合叠加 OSD
                float osdAlpha = osdColor.a * uOsdEnabled;
                gl_FragColor = mix(cameraColor, osdColor, osdAlpha);
            }
        """
        
        // 顶点坐标 (全屏四边形)
        private val VERTICES = floatArrayOf(
            -1f, -1f,  // 左下
             1f, -1f,  // 右下
            -1f,  1f,  // 左上
             1f,  1f   // 右上
        )
        
        // 纹理坐标 (相机 - 需要翻转Y轴)
        private val TEX_COORDS = floatArrayOf(
            0f, 1f,  // 左下
            1f, 1f,  // 右下
            0f, 0f,  // 左上
            1f, 0f   // 右上
        )
    }
    
    // EGL 相关
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE  // 编码器输出
    private var eglConfig: EGLConfig? = null  // 保存 EGL 配置
    
    // 预览 Surface 支持 (双输出)
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var previewSurface: Surface? = null
    private var previewWidth = 0
    private var previewHeight = 0
    
    // OpenGL 程序和着色器
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var osdTexCoordHandle = 0
    private var cameraTextureHandle = 0
    private var osdTextureHandle = 0
    private var osdEnabledHandle = 0
    private var brightnessHandle = 0
    private var contrastHandle = 0
    private var scaleXHandle = 0
    private var scaleYHandle = 0
    
    // 纹理
    private var cameraTextureId = 0
    private var osdTextureId = 0
    
    // 缓冲区
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var texCoordBuffer: FloatBuffer
    private lateinit var osdTexCoordBuffer: FloatBuffer
    
    // SurfaceTexture 用于接收相机帧
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    
    // 状态
    private val isInitialized = AtomicBoolean(false)
    private var osdEnabled = true
    private val transformMatrix = FloatArray(16)
    private var rotationDegrees = 0
    private var brightness = 1.0f  // 夜视亮度 (1.0 = 正常)
    private var contrast = 1.0f    // 夜视对比度 (1.0 = 正常)
    private var scaleX = 1.0f      // 水平缩放 (1.0 = 正常)
    private var scaleY = 1.0f      // 垂直缩放 (1.0 = 正常)
    
    // 帧可用回调
    private var frameAvailableListener: (() -> Unit)? = null
    
    // 不同旋转角度的纹理坐标
    private val TEX_COORDS_0 = floatArrayOf(
        0f, 1f,  1f, 1f,  0f, 0f,  1f, 0f  // 0° 原始
    )
    private val TEX_COORDS_90 = floatArrayOf(
        0f, 0f,  0f, 1f,  1f, 0f,  1f, 1f  // 90° 顺时针
    )
    private val TEX_COORDS_180 = floatArrayOf(
        1f, 0f,  0f, 0f,  1f, 1f,  0f, 1f  // 180°
    )
    private val TEX_COORDS_270 = floatArrayOf(
        1f, 1f,  1f, 0f,  0f, 1f,  0f, 0f  // 270° 逆时针
    )
    
    /**
     * 初始化渲染器
     * @param encoderSurface 编码器的输入 Surface
     */
    fun initialize(encoderSurface: Surface): Surface {
        if (isInitialized.get()) {
            throw IllegalStateException("Renderer already initialized")
        }
        
        initEGL(encoderSurface)
        initGL()
        initBuffers()
        initTextures()
        
        isInitialized.set(true)
        Log.d(TAG, "GLCameraRenderer initialized: ${width}x${height}")
        
        return cameraSurface!!
    }
    
    /**
     * 初始化 EGL 环境
     */
    private fun initEGL(outputSurface: Surface) {
        // 获取 EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }
        
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }
        
        // 配置 EGL
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, // 支持录制
            EGL14.EGL_NONE
        )
        
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
            throw RuntimeException("Unable to choose EGL config")
        }
        eglConfig = configs[0]  // 保存配置供后续创建预览 Surface
        
        // 创建 OpenGL ES 2.0 上下文
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Unable to create EGL context")
        }
        
        // 创建 EGL Surface (渲染到编码器)
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("Unable to create EGL surface")
        }
        
        // 激活上下文
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("Unable to make EGL context current")
        }
        
        Log.d(TAG, "EGL initialized successfully")
    }
    
    /**
     * 设置预览 Surface（用于双输出：编码器 + 手机预览）
     * @param surface 预览 Surface，null 表示移除预览
     */
    fun setPreviewSurface(surface: Surface?) {
        // 清理旧的预览 Surface
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
        previewSurface = surface
        
        // 创建新的预览 EGL Surface
        if (surface != null && surface.isValid && eglConfig != null) {
            try {
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, surfaceAttribs, 0
                )
                if (previewEglSurface == EGL14.EGL_NO_SURFACE) {
                    Log.w(TAG, "Failed to create preview EGL surface")
                } else {
                    // 查询预览 Surface 尺寸
                    val surfaceWidth = IntArray(1)
                    val surfaceHeight = IntArray(1)
                    EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
                    EGL14.eglQuerySurface(eglDisplay, previewEglSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
                    previewWidth = surfaceWidth[0]
                    previewHeight = surfaceHeight[0]
                    Log.d(TAG, "Preview surface attached: ${previewWidth}x${previewHeight}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach preview surface", e)
            }
        }
    }
    
    /**
     * 初始化 OpenGL 着色器程序
     */
    private fun initGL() {
        // 编译着色器
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER)
        
        // 创建程序
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Program link failed: $error")
        }
        
        // 获取属性和 uniform 位置
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        osdTexCoordHandle = GLES20.glGetAttribLocation(program, "aOsdTexCoord")
        cameraTextureHandle = GLES20.glGetUniformLocation(program, "uCameraTexture")
        osdTextureHandle = GLES20.glGetUniformLocation(program, "uOsdTexture")
        osdEnabledHandle = GLES20.glGetUniformLocation(program, "uOsdEnabled")
        brightnessHandle = GLES20.glGetUniformLocation(program, "uBrightness")
        contrastHandle = GLES20.glGetUniformLocation(program, "uContrast")
        scaleXHandle = GLES20.glGetUniformLocation(program, "uScaleX")
        scaleYHandle = GLES20.glGetUniformLocation(program, "uScaleY")
        
        // 删除着色器对象 (已链接到程序)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        
        Log.d(TAG, "OpenGL shaders compiled and linked")
    }
    
    /**
     * 编译着色器
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile failed: $error")
        }
        
        return shader
    }
    
    /**
     * 初始化顶点和纹理坐标缓冲区
     */
    private fun initBuffers() {
        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTICES)
        vertexBuffer.position(0)
        
        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEX_COORDS)
        texCoordBuffer.position(0)
        
        // OSD 纹理坐标始终使用固定坐标（不跟随相机旋转）
        osdTexCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS_0.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEX_COORDS_0)
        osdTexCoordBuffer.position(0)
    }
    
    /**
     * 初始化纹理
     */
    private fun initTextures() {
        // 创建相机纹理 (外部纹理)
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 创建 SurfaceTexture 用于接收相机帧
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId).apply {
            setDefaultBufferSize(width, height)
            setOnFrameAvailableListener { 
                frameAvailableListener?.invoke()
            }
        }
        cameraSurface = Surface(cameraSurfaceTexture)
        
        // 创建 OSD 纹理 (普通 2D 纹理)
        osdTextureId = textures[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // 初始化透明 OSD 纹理
        val emptyBuffer = ByteBuffer.allocateDirect(width * height * 4)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, emptyBuffer
        )
        
        Log.d(TAG, "Textures initialized. Camera: $cameraTextureId, OSD: $osdTextureId")
    }
    
    /**
     * 更新 OSD 纹理
     * @param osdBitmap OSD 水印的 Bitmap
     */
    fun updateOsdTexture(osdBitmap: Bitmap) {
        if (!isInitialized.get()) return
        
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, osdBitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }
    
    /**
     * 设置 OSD 是否启用
     */
    fun setOsdEnabled(enabled: Boolean) {
        osdEnabled = enabled
    }
    
    /**
     * 设置夜视增强参数
     * @param enabled 是否启用夜视增强
     * @param brightnessBoost 亮度增强 (1.0 = 正常, 2.5 = 增强 150%)
     * @param contrastBoost 对比度增强 (1.0 = 正常, 1.4 = 增强 40%)
     */
    fun setNightVision(enabled: Boolean, brightnessBoost: Float = 2.5f, contrastBoost: Float = 1.4f) {
        if (enabled) {
            brightness = brightnessBoost
            contrast = contrastBoost
        } else {
            brightness = 1.0f
            contrast = 1.0f
        }
        Log.d(TAG, "Night vision: enabled=$enabled, brightness=$brightness, contrast=$contrast")
    }
    
    /**
     * 设置画面缩放比例（用于手动调整宽高比）
     * @param x 水平缩放 (1.0 = 正常, <1.0 = 压缩, >1.0 = 拉伸)
     * @param y 垂直缩放 (1.0 = 正常, <1.0 = 压缩, >1.0 = 拉伸)
     */
    fun setScale(x: Float, y: Float) {
        scaleX = x.coerceIn(0.5f, 1.5f)  // 限制范围 50%-150%
        scaleY = y.coerceIn(0.5f, 1.5f)
        Log.d(TAG, "Scale: scaleX=$scaleX, scaleY=$scaleY")
    }
    
    /**
     * 设置帧可用回调
     */
    fun setOnFrameAvailableListener(listener: () -> Unit) {
        frameAvailableListener = listener
    }
    
    /**
     * 设置画面旋转角度
     * @param degrees 旋转角度，支持 0, 90, 180, 270
     */
    fun setRotation(degrees: Int) {
        rotationDegrees = degrees
        updateTexCoordBuffer()
    }
    
    /**
     * 根据旋转角度更新纹理坐标缓冲区
     */
    private fun updateTexCoordBuffer() {
        val coords = when (rotationDegrees) {
            90 -> TEX_COORDS_90
            180 -> TEX_COORDS_180
            270 -> TEX_COORDS_270
            else -> TEX_COORDS_0
        }
        texCoordBuffer.clear()
        texCoordBuffer.put(coords)
        texCoordBuffer.position(0)
    }
    
    /**
     * 渲染一帧
     * 将相机帧与 OSD 合成并输出到编码器和预览
     */
    fun drawFrame() {
        if (!isInitialized.get()) return
        
        // 更新相机纹理
        try {
            cameraSurfaceTexture?.updateTexImage()
            cameraSurfaceTexture?.getTransformMatrix(transformMatrix)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update camera texture: ${e.message}")
            return
        }
        
        // 渲染到编码器
        renderToSurface(eglSurface)
        
        // 同时渲染到预览 (如果有)
        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                renderToSurface(previewEglSurface)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to render to preview: ${e.message}")
            }
        }
    }
    
    /**
     * 渲染到指定的 EGL Surface
     */
    private fun renderToSurface(targetSurface: EGLSurface) {
        // 切换到目标 Surface
        if (!EGL14.eglMakeCurrent(eglDisplay, targetSurface, targetSurface, eglContext)) {
            Log.w(TAG, "Failed to make surface current")
            return
        }
        
        // 查询目标 Surface 尺寸
        val surfaceWidth = IntArray(1)
        val surfaceHeight = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, targetSurface, EGL14.EGL_WIDTH, surfaceWidth, 0)
        EGL14.eglQuerySurface(eglDisplay, targetSurface, EGL14.EGL_HEIGHT, surfaceHeight, 0)
        val targetWidth = surfaceWidth[0]
        val targetHeight = surfaceHeight[0]
        
        // 设置视口（使用查询到的尺寸）
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // 使用着色器程序
        GLES20.glUseProgram(program)
        
        // 设置顶点坐标
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        // 设置纹理坐标（相机，跟随旋转）
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        
        // 设置 OSD 纹理坐标（固定，不旋转）
        GLES20.glEnableVertexAttribArray(osdTexCoordHandle)
        GLES20.glVertexAttribPointer(osdTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, osdTexCoordBuffer)
        
        // 绑定相机纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(cameraTextureHandle, 0)
        
        // 绑定 OSD 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTextureId)
        GLES20.glUniform1i(osdTextureHandle, 1)
        
        // 设置 OSD 启用状态
        GLES20.glUniform1f(osdEnabledHandle, if (osdEnabled) 1.0f else 0.0f)
        
        // 设置夜视增强参数
        GLES20.glUniform1f(brightnessHandle, brightness)
        GLES20.glUniform1f(contrastHandle, contrast)
        
        // 设置画面缩放
        GLES20.glUniform1f(scaleXHandle, scaleX)
        GLES20.glUniform1f(scaleYHandle, scaleY)
        
        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // 禁用顶点属性
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDisableVertexAttribArray(osdTexCoordHandle)
        
        // 交换缓冲区
        EGL14.eglSwapBuffers(eglDisplay, targetSurface)
    }
    
    /**
     * 设置呈现时间戳（用于编码器同步）
     */
    fun setPresentationTime(nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }
    
    /**
     * 释放资源
     */
    fun release() {
        if (!isInitialized.getAndSet(false)) return
        
        // 释放 Surface
        cameraSurface?.release()
        cameraSurface = null
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null
        
        // 删除纹理
        val textures = intArrayOf(cameraTextureId, osdTextureId)
        GLES20.glDeleteTextures(2, textures, 0)
        
        // 删除程序
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        
        // 释放 EGL 资源
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            // 释放预览 Surface
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
        
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        previewEglSurface = EGL14.EGL_NO_SURFACE
        previewSurface = null
        
        Log.d(TAG, "GLCameraRenderer released")
    }
}
