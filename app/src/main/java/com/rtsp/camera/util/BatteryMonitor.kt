package com.rtsp.camera.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * 电池状态监视器
 */
class BatteryMonitor(private val context: Context) {
    
    private var batteryReceiver: BroadcastReceiver? = null
    private var onBatteryChanged: ((BatteryInfo) -> Unit)? = null
    
    /**
     * 电池信息数据类
     */
    data class BatteryInfo(
        val level: Int,           // 电量百分比 0-100
        val temperature: Float,   // 温度 (摄氏度)
        val voltage: Int,         // 电压 (mV)
        val isCharging: Boolean,  // 是否充电中
        val plugType: PlugType    // 充电类型
    )
    
    enum class PlugType {
        NONE, AC, USB, WIRELESS
    }
    
    /**
     * 获取当前电池状态（一次性查询）
     */
    fun getBatteryInfo(): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return parseBatteryIntent(intent)
    }
    
    /**
     * 启动电池状态监听
     */
    fun startMonitoring(callback: (BatteryInfo) -> Unit) {
        onBatteryChanged = callback
        
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val info = parseBatteryIntent(intent)
                onBatteryChanged?.invoke(info)
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        
        context.registerReceiver(batteryReceiver, filter)
    }
    
    /**
     * 停止监听
     */
    fun stopMonitoring() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) {}
        }
        batteryReceiver = null
        onBatteryChanged = null
    }
    
    /**
     * 解析电池广播Intent
     */
    private fun parseBatteryIntent(intent: Intent?): BatteryInfo {
        if (intent == null) {
            return BatteryInfo(100, 25f, 0, false, PlugType.NONE)
        }
        
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val levelPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else 100
        
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) / 10f
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val plugType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> PlugType.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PlugType.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PlugType.WIRELESS
            else -> PlugType.NONE
        }
        
        return BatteryInfo(levelPercent, temperature, voltage, isCharging, plugType)
    }
}
