package com.example.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class AppNetworkProxyTest {
    private val origin = URI("http://catalog.example.invalid/track")

    @Test fun startupRouteIsLazyAndStableUntilRestart() {
        var saved = settings(port = 8080)
        var reads = 0
        val selector = SettingsProxySelector(null) { reads++; saved }
        assertEquals(0, reads)
        val original = selector.select(origin).single()
        saved = settings(port = 9000, protocol = "SOCKS5")
        assertEquals(original, selector.select(origin).single())
        assertEquals(1, reads)
        assertEquals(saved.validatedProxy(), SettingsProxySelector(null) { saved }.select(origin).single())
    }

    @Test fun disabledKeepsPlatformRoutingAndFailuresAreNeverRetriedDirectlyWhenEnabled() {
        var platformFailures = 0
        val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system.example", 8080))
        val platform = object : ProxySelector() {
            override fun select(uri: URI) = listOf(systemProxy, Proxy.NO_PROXY)
            override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) { platformFailures++ }
        }
        val inactive = SettingsProxySelector(platform) { ProxySettings() }
        assertEquals(platform.select(origin), inactive.select(origin))
        inactive.connectFailed(origin, systemProxy.address(), IOException())
        assertEquals(1, platformFailures)

        for (configuration in listOf(settings(port = 9000), settings(port = 0))) {
            val active = SettingsProxySelector(platform) { configuration }
            val selected = active.select(origin).single()
            assertNotEquals(Proxy.NO_PROXY, selected)
            assertNotEquals(systemProxy, selected)
            active.connectFailed(origin, selected.address(), IOException())
        }
        assertEquals(1, platformFailures)
        assertSame(platform, inactive.platform)
    }

    @Test fun validatesEndpointsWithoutAcceptingUrlsOrInvalidPorts() {
        assertNull(settings(0).validatedProxy())
        assertNull(settings(65_536).validatedProxy())
        assertNull(settings(1080, "unknown").validatedProxy())
        for (value in listOf("", "https://host", "user@host", "host/path", "proxy example", "-host", "a..b"))
            assertNull(value, settings(1080).copy(host = value).validatedProxy())
        assertEquals("proxy.example", (settings(65_535).copy(host = " Proxy.Example ").validatedProxy()?.address()
            as InetSocketAddress).hostString)
        assertEquals("::1", (settings(1080).copy(host = "[::1]").validatedProxy()?.address()
            as InetSocketAddress).hostString)
    }

    @Test fun supportsActualHttpProxyRequestsFromOkHttpAndUrlConnection() {
        val targetLine = "GET ${origin.toASCIIString()} HTTP/1.1"
        assertEquals(targetLine, useServer(::respondToHttp) { port ->
            val selector = SettingsProxySelector(null) { settings(port) }
            val client = OkHttpClient.Builder().proxySelector(selector).callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(origin.toASCIIString()).build()).execute().use {
                assertEquals("proxied", it.body.string())
            }
        })
        assertEquals(targetLine, useServer(::respondToHttp) { port ->
            val original = ProxySelector.getDefault()
            try {
                ProxySelector.setDefault(SettingsProxySelector(original) { settings(port) })
                val connection = origin.toURL().openConnection().apply {
                    connectTimeout = 3_000
                    readTimeout = 3_000
                }
                assertEquals("proxied", connection.getInputStream().bufferedReader().use { it.readText() })
            } finally { ProxySelector.setDefault(original) }
        })
    }

    @Test fun socks5SelectsATunnelAndLeavesDestinationResolutionToTheProxy() {
        val path = useServer({ socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            assertEquals(5, input.readUnsignedByte())
            val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
            assertTrue(0.toByte() in methods)
            output.write(byteArrayOf(5, 0))
            output.flush()

            assertEquals(5, input.readUnsignedByte())
            assertEquals(1, input.readUnsignedByte()) // CONNECT
            assertEquals(0, input.readUnsignedByte())
            assertEquals(3, input.readUnsignedByte()) // Domain name
            val host = ByteArray(input.readUnsignedByte()).also(input::readFully).decodeToString()
            assertEquals(origin.host, host)
            assertEquals(80, input.readUnsignedShort())
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            output.flush()
            respondToHttp(socket)
        }) { port ->
            val selector = SettingsProxySelector(null) { settings(port, "SOCKS5") }
            val client = OkHttpClient.Builder().proxySelector(selector).callTimeout(5, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(origin.toASCIIString()).build()).execute().use {
                assertEquals("proxied", it.body.string())
            }
        }
        assertEquals("GET /track HTTP/1.1", path)
    }

    private fun settings(port: Int, protocol: String = "HTTP") = ProxySettings(true, "127.0.0.1", port, protocol)

    private fun respondToHttp(socket: Socket): String {
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val first = checkNotNull(reader.readLine())
        while (!reader.readLine().isNullOrEmpty()) Unit
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nproxied".toByteArray())
            flush()
        }
        return first
    }

    private fun <T> useServer(handle: (Socket) -> T, client: (Int) -> Unit): T =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val result = FutureTask { server.accept().use { connection ->
                connection.soTimeout = 5_000
                handle(connection)
            } }
            Thread(result, "Proxy test server").apply { isDaemon = true }.start()
            client(server.localPort)
            result.get(5, TimeUnit.SECONDS)
        }
}
