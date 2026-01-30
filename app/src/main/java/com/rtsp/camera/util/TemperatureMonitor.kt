package com.rtsp.camera.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.util.Log
import java.io.File

/**
 * 温度传感器管理器
 * 支持读取设备上所有可用的温度传感器
 */
class TemperatureMonitor(private val context: Context) : SensorEventListener {
    
    companion object {
        private const val TAG = "TemperatureMonitor"
    }
    
    /**
     * 温度传感器信息
     */
    data class TempSensor(
        val id: String,           // 传感器ID
        val name: String,         // 显示名称
        val type: TempSourceType  // 类型
    )
    
    enum class TempSourceType {
        BATTERY,      // 电池温度
        CPU,          // CPU温度 (thermal zone)
        SENSOR,       // Android传感器框架
        AMBIENT       // 环境温度传感器
    }
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var currentSensor: Sensor? = null
    private var currentTemperature: Float = 0f
    private var selectedSensorId: String = "battery"  // 默认电池
    
    private var onTemperatureChanged: ((Float) -> Unit)? = null
    
    /**
     * 获取所有可用的温度传感器
     */
    fun getAvailableSensors(): List<TempSensor> {
        val sensors = mutableListOf<TempSensor>()
        
        // 1. 电池温度（始终可用）
        sensors.add(TempSensor("battery", "电池温度", TempSourceType.BATTERY))
        
        // 2. Android 温度传感器
        val ambientSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        if (ambientSensor != null) {
            sensors.add(TempSensor("ambient", "环境温度 (${ambientSensor.name})", TempSourceType.AMBIENT))
        }
        
        // 3. 所有温度相关的硬件传感器
        val tempSensors = sensorManager.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
            sensor.name.lowercase().contains("temp") ||
            sensor.name.lowercase().contains("therm")
        }
        for (sensor in tempSensors) {
            sensors.add(TempSensor("sensor_${sensor.type}", sensor.name, TempSourceType.SENSOR))
        }
        
        // 4. 读取 thermal zones (CPU等硬件温度)
        val thermalZones = readThermalZones()
        for ((zoneId, zoneName) in thermalZones) {
            sensors.add(TempSensor("thermal_$zoneId", zoneName, TempSourceType.CPU))
        }
        
        return sensors
    }
    
    /**
     * 读取 Linux thermal zones
     */
    private fun readThermalZones(): List<Pair<Int, String>> {
        val zones = mutableListOf<Pair<Int, String>>()
        try {
            val thermalDir = File("/sys/class/thermal")
            if (thermalDir.exists()) {
                thermalDir.listFiles()?.forEach { dir ->
                    if (dir.name.startsWith("thermal_zone")) {
                        val zoneId = dir.name.removePrefix("thermal_zone").toIntOrNull() ?: return@forEach
                        val typeFile = File(dir, "type")
                        val typeName = if (typeFile.exists()) {
                            typeFile.readText().trim()
                        } else {
                            "Zone $zoneId"
                        }
                        zones.add(Pair(zoneId, typeName))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading thermal zones", e)
        }
        return zones.sortedBy { it.first }
    }
    
    /**
     * 设置当前使用的传感器
     */
    fun setSelectedSensor(sensorId: String) {
        selectedSensorId = sensorId
        
        // 如果之前有监听，重新配置
        if (onTemperatureChanged != null) {
            stopMonitoring()
            startMonitoring(onTemperatureChanged!!)
        }
    }
    
    /**
     * 获取当前选择的传感器ID
     */
    fun getSelectedSensorId(): String = selectedSensorId
    
    /**
     * 开始监听温度变化
     */
    fun startMonitoring(callback: (Float) -> Unit) {
        onTemperatureChanged = callback
        
        when {
            selectedSensorId == "battery" -> {
                // 电池温度不需要注册传感器，在 getTemperature 中读取
            }
            selectedSensorId == "ambient" -> {
                currentSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
                currentSensor?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            selectedSensorId.startsWith("sensor_") -> {
                val sensorType = selectedSensorId.removePrefix("sensor_").toIntOrNull()
                if (sensorType != null) {
                    currentSensor = sensorManager.getDefaultSensor(sensorType)
                    currentSensor?.let {
                        sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                    }
                }
            }
            // thermal zones 不需要注册监听
        }
    }
    
    /**
     * 停止监听
     */
    fun stopMonitoring() {
        currentSensor?.let {
            sensorManager.unregisterListener(this)
        }
        currentSensor = null
        onTemperatureChanged = null
    }
    
    /**
     * 获取当前温度
     */
    fun getTemperature(): Float {
        return when {
            selectedSensorId == "battery" -> {
                getBatteryTemperature()
            }
            selectedSensorId.startsWith("thermal_") -> {
                val zoneId = selectedSensorId.removePrefix("thermal_").toIntOrNull() ?: 0
                readThermalZoneTemp(zoneId)
            }
            else -> {
                currentTemperature
            }
        }
    }
    
    /**
     * 获取电池温度
     */
    private fun getBatteryTemperature(): Float {
        // 使用广播方式获取电池温度
        val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250) / 10f
    }
    
    /**
     * 读取 thermal zone 温度
     */
    private fun readThermalZoneTemp(zoneId: Int): Float {
        return try {
            val tempFile = File("/sys/class/thermal/thermal_zone$zoneId/temp")
            if (tempFile.exists() && tempFile.canRead()) {
                val temp = tempFile.readText().trim().toIntOrNull() ?: 0
                // 大多数设备返回毫摄氏度
                if (temp > 1000) temp / 1000f else temp.toFloat()
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading thermal zone $zoneId", e)
            0f
        }
    }
    
    // SensorEventListener
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            currentTemperature = it.values[0]
            onTemperatureChanged?.invoke(currentTemperature)
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 不需要处理
    }
}
