package com.rtsp.camera.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.rtsp.camera.service.RTSPService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 高级保活管理器
 * 综合使用多种策略确保服务长时间运行
 */
class KeepAliveManager(private val context: Context) {
    
    companion object {
        private const val TAG = "KeepAliveManager"
        private const val ALARM_INTERVAL = 5 * 60 * 1000L  // 5分钟
        private const val ACTION_KEEP_ALIVE = "com.rtsp.camera.action.KEEP_ALIVE"
        private const val WAKELOCK_TIMEOUT = 24 * 60 * 60 * 1000L  // 24小时
    }
    
    private val isActive = AtomicBoolean(false)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    
    private var alarmManager: AlarmManager? = null
    private var alarmIntent: PendingIntent? = null
    
    private var screenReceiver: BroadcastReceiver? = null
    
    // 保活策略回调
    var onKeepAliveCheck: (() -> Boolean)? = null  // 返回true表示服务正常
    var onServiceDied: (() -> Unit)? = null
    
    /**
     * 启动保活机制
     */
    fun start() {
        if (isActive.getAndSet(true)) return
        
        Log.i(TAG, "Starting keep-alive manager")
        
        // 1. 获取CPU唤醒锁
        acquireWakeLock()
        
        // 2. 获取WiFi锁（保持WiFi连接）
        acquireWifiLock()
        
        // 3. 设置定时唤醒闹钟
        setupAlarm()
        
        // 4. 监听屏幕事件
        registerScreenReceiver()
        
        Log.i(TAG, "Keep-alive manager started with all strategies")
    }
    
    /**
     * 停止保活机制
     */
    fun stop() {
        if (!isActive.getAndSet(false)) return
        
        Log.i(TAG, "Stopping keep-alive manager")
        
        cancelAlarm()
        unregisterScreenReceiver()
        releaseWifiLock()
        releaseWakeLock()
    }
    
    /**
     * 获取CPU唤醒锁
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RTSPCamera::KeepAliveWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(WAKELOCK_TIMEOUT)
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }
    }
    
    /**
     * 释放CPU唤醒锁
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wakeLock = null
            Log.d(TAG, "WakeLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }
    }
    
    /**
     * 获取WiFi锁
     */
    private fun acquireWifiLock() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            
            wifiLock = wifiManager.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "RTSPCamera::KeepAliveWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock", e)
        }
    }
    
    /**
     * 释放WiFi锁
     */
    private fun releaseWifiLock() {
        try {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
            wifiLock = null
            Log.d(TAG, "WifiLock released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WifiLock", e)
        }
    }
    
    /**
     * 设置定时唤醒闹钟
     */
    private fun setupAlarm() {
        try {
            alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val intent = Intent(ACTION_KEEP_ALIVE).apply {
                setPackage(context.packageName)
            }
            
            alarmIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // 注册接收器
            context.registerReceiver(
                alarmReceiver,
                IntentFilter(ACTION_KEEP_ALIVE),
                Context.RECEIVER_NOT_EXPORTED
            )
            
            // 设置重复闹钟
            val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    alarmIntent!!
                )
            } else {
                alarmManager?.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    alarmIntent!!
                )
            }
            
            Log.d(TAG, "Keep-alive alarm scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup alarm", e)
        }
    }
    
    /**
     * 闹钟接收器
     */
    private val alarmReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_KEEP_ALIVE) {
                Log.d(TAG, "Keep-alive alarm triggered")
                
                // 检查服务状态
                val isAlive = onKeepAliveCheck?.invoke() ?: true
                
                if (!isAlive) {
                    Log.w(TAG, "Service appears to be dead, attempting restart")
                    onServiceDied?.invoke()
                }
                
                // 重新调度下一次闹钟
                if (isActive.get()) {
                    scheduleNextAlarm()
                }
            }
        }
    }
    
    /**
     * 调度下一次闹钟
     */
    private fun scheduleNextAlarm() {
        val triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager?.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                alarmIntent!!
            )
        } else {
            alarmManager?.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                alarmIntent!!
            )
        }
    }
    
    /**
     * 取消闹钟
     */
    private fun cancelAlarm() {
        try {
            alarmIntent?.let { alarmManager?.cancel(it) }
            context.unregisterReceiver(alarmReceiver)
        } catch (_: Exception) {}
    }
    
    /**
     * 注册屏幕状态接收器
     */
    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d(TAG, "Screen OFF - ensuring wake lock")
                        // 屏幕关闭时确保唤醒锁有效
                        if (wakeLock?.isHeld != true) {
                            acquireWakeLock()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen ON")
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(screenReceiver, filter)
    }
    
    /**
     * 注销屏幕状态接收器
     */
    private fun unregisterScreenReceiver() {
        try {
            screenReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        screenReceiver = null
    }
    
    /**
     * 检查保活状态
     */
    fun isActive(): Boolean = isActive.get()
    
    /**
     * 获取保活状态详情
     */
    fun getStatusReport(): Map<String, Boolean> {
        return mapOf(
            "active" to isActive.get(),
            "wakeLock" to (wakeLock?.isHeld == true),
            "wifiLock" to (wifiLock?.isHeld == true)
        )
    }
}
