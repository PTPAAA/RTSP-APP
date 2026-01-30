package com.rtsp.camera.model

/**
 * 网络配置
 */
data class NetworkConfig(
    val port: Int = 8554,
    val ipv4Only: Boolean = false,
    val lanOnly: Boolean = false,
    // RTSP 鉴权配置
    val enableAuth: Boolean = false,
    val username: String = "",
    val password: String = ""
)
