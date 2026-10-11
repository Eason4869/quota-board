package com.yusheng.quota

import com.yusheng.quota.net.CodexProxy
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class CodexProxyConfigTest {
    @Test fun blankLeavesSystemNetworkUnchanged() { assertNull(CodexProxy.parse("  ")) }
    @Test fun parsesHttpAndSocksWithoutGlobalSideEffects() {
        val before = System.getProperty("http.proxyHost")
        assertEquals(Proxy.Type.HTTP, CodexProxy.parse("http://127.0.0.1:7890")!!.type())
        val socks = CodexProxy.parse("socks5://localhost:1080")!!
        assertEquals(Proxy.Type.SOCKS, socks.type())
        assertEquals(1080, (socks.address() as InetSocketAddress).port)
        assertEquals(before, System.getProperty("http.proxyHost"))
    }
    @Test fun rejectsCredentialsAmbiguousEndpointsAndInvalidPorts() {
        for (raw in listOf("https://host:7890", "http://user:pass@host:7890", "http://host",
            "http://host:0", "http://host:65536", "socks5://host:1080/path", "http://host:7890?key=secret",
            "http://host:7890#fragment", "not a proxy")) {
            assertThrows("Must reject $raw", Exception::class.java) { CodexProxy.parse(raw) }
        }
    }
}
