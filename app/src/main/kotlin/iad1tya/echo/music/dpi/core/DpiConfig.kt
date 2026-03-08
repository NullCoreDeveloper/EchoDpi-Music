package iad1tya.echo.music.dpi.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.Protocol

object DpiConfig {
    val IsDpiConfiguredKey = booleanPreferencesKey("is_dpi_configured")
    val DpiEnabledKey = booleanPreferencesKey("dpi_enabled")
    val DpiStrategyKey = stringPreferencesKey("dpi_strategy")
    val DpiCustomParamsKey = stringPreferencesKey("dpi_custom_params")
    val AutoDisableDpiOnVpnKey = booleanPreferencesKey("auto_disable_dpi_on_vpn")

    // In-memory cache for fast synchronous access
    @Volatile var isEnabled = true
    @Volatile var autoDisableOnVpn = false
    @Volatile var currentStrategy = DpiStrategy.DEFAULT
    @Volatile var customParams = ""

    fun applyTo(builder: okhttp3.OkHttpClient.Builder, context: android.content.Context? = null): okhttp3.OkHttpClient.Builder {
        return builder.applyDpi(context)
    }
}

class DpiDns : Dns {
    private val cache = mutableMapOf<String, Pair<List<InetAddress>, Long>>()
    private val cacheTtlMs = 10 * 60 * 1000L // 10 minutes

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (addresses, timestamp) ->
            if (now - timestamp < cacheTtlMs) {
                return addresses
            }
        }

        val addresses = Dns.SYSTEM.lookup(hostname)
        val result = if (!DpiConfig.isEnabled || (DpiConfig.currentStrategy == DpiStrategy.DEFAULT && DpiConfig.customParams.isBlank())) {
            addresses
        } else {
            val ipv4Only = addresses.filterIsInstance<Inet4Address>()
            ipv4Only.ifEmpty { addresses }
        }
        
        synchronized(cache) {
            cache[hostname] = result to now
        }
        return result
    }
}

fun okhttp3.OkHttpClient.Builder.applyDpi(context: android.content.Context? = null): okhttp3.OkHttpClient.Builder {
    try {
        val shouldDisableDpi = DpiConfig.autoDisableOnVpn && context != null && iad1tya.echo.music.utils.isVpnConnected(context)
        
        if (DpiConfig.isEnabled && !shouldDisableDpi && (DpiConfig.currentStrategy != DpiStrategy.DEFAULT || DpiConfig.customParams.isNotBlank())) {
            LocalDpiProxyServer.start()
            if (LocalDpiProxyServer.port > 0) {
                proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", LocalDpiProxyServer.port)))
            }
        } else {
            proxy(java.net.Proxy.NO_PROXY)
        }
        dns(DpiDns())
        protocols(listOf(Protocol.HTTP_1_1))
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return this
}
