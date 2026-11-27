package com.example.network

import android.content.Context
import com.example.data.local.OtherSettingsPreferences
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

internal data class ProxySettings(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val protocol: String = "HTTP"
) {
    fun validatedProxy(): Proxy? {
        val type = when {
            protocol.equals("HTTP", ignoreCase = true) -> Proxy.Type.HTTP
            protocol.equals("SOCKS5", ignoreCase = true) -> Proxy.Type.SOCKS
            else -> return null
        }
        val address = host.trim()
        if (address.isEmpty() || address.any { it.isWhitespace() || it in "/?#@\\%" } || port !in 1..65_535)
            return null
        val normalized = runCatching { HttpUrl.Builder().scheme("http").host(address).port(port).build().host }
            .getOrNull() ?: return null
        if (':' !in normalized && normalized.removeSuffix(".").split('.').any { label ->
                label.length !in 1..63 || label.first() == '-' || label.last() == '-' ||
                    label.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' }
            }) return null
        return Proxy(type, InetSocketAddress.createUnresolved(normalized, port))
    }

    fun sameRoute(other: ProxySettings): Boolean =
        enabled == other.enabled && (!enabled || validatedProxy() == other.validatedProxy())
}

/**
 * A process keeps one saved route so HTTP/2 and media connection pools cannot reuse a
 * socket opened under an earlier proxy setting. Activate edited settings on restart.
 */
internal class SettingsProxySelector(
    val platform: ProxySelector?,
    loadSettings: () -> ProxySettings
) : ProxySelector() {
    val settings by lazy(loadSettings)
    private val configuredRoute by lazy {
        // Port zero cannot connect to an upstream. Reject a legacy invalid enabled
        // setting without allowing the platform to retry it as a direct connection.
        if (settings.enabled) listOf(settings.validatedProxy()
            ?: Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("127.0.0.1", 0)))
        else null
    }

    private fun managed(uri: URI) = uri.scheme == "http" || uri.scheme == "https"

    override fun select(uri: URI): List<Proxy> =
        (if (managed(uri)) configuredRoute else null) ?: platform?.select(uri) ?: listOf(Proxy.NO_PROXY)

    override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) {
        if (!managed(uri) || configuredRoute == null) platform?.connectFailed(uri, address, error)
    }
}

internal object AppNetworkProxy {
    @Volatile private var selector: SettingsProxySelector? = null

    @Synchronized
    fun install(context: Context) {
        val platform = ProxySelector.getDefault()
        if (platform === selector && selector != null) return
        // Capture the selector before creating any clients. Read preferences only
        // on the first network worker or on the proxy settings screen's IO lane.
        val configured = SettingsProxySelector(platform) {
            OtherSettingsPreferences.readPersistedProxySettings(context)
        }
        ProxySelector.setDefault(configured)
        selector = configured
    }

    fun activeSettings(): ProxySettings? = selector?.settings

    fun platformSelector(): ProxySelector? {
        val installed = selector
        return if (installed == null) ProxySelector.getDefault() else installed.platform
    }
}
