package com.rtsp.camera.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 网络工具类
 */
object NetworkUtils {
    
    /**
     * 获取设备IP地址
     * @param context Context
     * @param ipv4Only 是否仅返回IPv4地址
     * @param lanOnly 是否仅返回局域网地址
     * @return IP地址列表
     */
    fun getDeviceIpAddresses(
        context: Context,
        ipv4Only: Boolean = false,
        lanOnly: Boolean = false
    ): List<String> {
        val addresses = mutableListOf<String>()
        
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // 跳过回环接口和未启用的接口
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val inetAddresses = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    
                    // 跳过回环地址
                    if (address.isLoopbackAddress) continue
                    
                    // IPv4过滤
                    if (ipv4Only && address !is Inet4Address) continue
                    
                    // 局域网过滤
                    if (lanOnly && !isPrivateAddress(address)) continue
                    
                    val hostAddress = address.hostAddress ?: continue
                    
                    // 移除IPv6的作用域ID（如 %wlan0）
                    val cleanAddress = if (address is Inet6Address) {
                        hostAddress.split("%")[0]
                    } else {
                        hostAddress
                    }
                    
                    addresses.add(cleanAddress)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return addresses
    }
    
    /**
     * 获取主要的WiFi IP地址
     */
    fun getWifiIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo
        val ipInt = wifiInfo?.ipAddress ?: return null
        
        if (ipInt == 0) return null
        
        return String.format(
            "%d.%d.%d.%d",
            ipInt and 0xff,
            ipInt shr 8 and 0xff,
            ipInt shr 16 and 0xff,
            ipInt shr 24 and 0xff
        )
    }
    
    /**
     * 检查是否有可用网络
     */
    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    
    /**
     * 判断是否为私有（局域网）地址
     */
    private fun isPrivateAddress(address: InetAddress): Boolean {
        return address.isSiteLocalAddress || address.isLinkLocalAddress
    }
    
    /**
     * 构建RTSP URL
     */
    fun buildRtspUrl(ip: String, port: Int, path: String = "live"): String {
        return if (ip.contains(":")) {
            // IPv6地址需要用方括号包围
            "rtsp://[$ip]:$port/$path"
        } else {
            "rtsp://$ip:$port/$path"
        }
    }
}
