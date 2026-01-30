package com.rtsp.camera.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log

/**
 * 光线传感器管理器
 * 用于自动控制闪光灯
 */
class LightSensorManager(context: Context) : SensorEventListener {
    
    companion object {
        private const val TAG = "LightSensorManager"
    }
    
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    
    private var currentLux: Float = 0f
    private var onLightLevelChanged: ((Float) -> Unit)? = null
    
    /**
     * 是否支持光线传感器
     */
    fun isSupported(): Boolean = lightSensor != null
    
    /**
     * 获取当前光照强度 (lux)
     */
    fun getCurrentLux(): Float = currentLux
    
    /**
     * 设置光照变化监听器
     */
    fun setOnLightLevelChangedListener(listener: (Float) -> Unit) {
        onLightLevelChanged = listener
    }
    
    /**
     * 开始监听
     */
    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Light sensor started")
        }
    }
    
    /**
     * 停止监听
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Light sensor stopped")
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_LIGHT) {
            currentLux = event.values[0]
            onLightLevelChanged?.invoke(currentLux)
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理
    }
    
    /**
     * 判断是否需要开启闪光灯
     * @param threshold 阈值 (lux)
     */
    fun shouldEnableFlash(threshold: Float): Boolean {
        return currentLux < threshold
    }
}
