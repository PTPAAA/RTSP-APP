package com.rtsp.camera.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rtsp.camera.service.RTSPService
import com.rtsp.camera.util.PreferenceHelper

/**
 * 开机自启动接收器
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = PreferenceHelper(context)
            
            if (prefs.autoStart) {
                Log.i(TAG, "Auto-starting RTSP service after boot")
                
                val serviceIntent = Intent(context, RTSPService::class.java).apply {
                    action = RTSPService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
