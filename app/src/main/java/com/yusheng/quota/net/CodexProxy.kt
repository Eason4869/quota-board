package com.yusheng.quota.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/** Per-connection proxy; never changes process-wide proxy or TLS trust settings. */
object CodexProxy {
    fun parse(raw: String): Proxy? {
        if (raw.isBlank()) return null
        val uri = URI(raw.trim())
        val type = when (uri.scheme?.lowercase()) {
            "http" -> Proxy.Type.HTTP
            "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalArgumentException("Unsupported proxy scheme")
        }
        require(!uri.host.isNullOrBlank() && uri.port in 1..65535)
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
        return Proxy(type, InetSocketAddress.createUnresolved(uri.host, uri.port))
    }
}
