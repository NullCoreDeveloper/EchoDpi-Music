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

    // In-memory cache for fast synchronous access
    @Volatile var isEnabled = true
    @Volatile var currentStrategy = DpiStrategy.DEFAULT
    @Volatile var customParams = ""

    fun applyTo(builder: okhttp3.OkHttpClient.Builder) = builder.applyDpi()
}

class DpiDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        if (!DpiConfig.isEnabled || (DpiConfig.currentStrategy == DpiStrategy.DEFAULT && DpiConfig.customParams.isBlank())) {
            return addresses
        }
        val ipv4Only = addresses.filterIsInstance<Inet4Address>()
        return ipv4Only.ifEmpty { addresses }
    }
}

fun okhttp3.OkHttpClient.Builder.applyDpi(): okhttp3.OkHttpClient.Builder {
    try {
        val defaultFactory = javax.net.SocketFactory.getDefault()
        val dpiFactory = DpiSocketFactory(
            delegate = defaultFactory,
            getStrategy = { DpiConfig.currentStrategy },
            getCustomParams = { DpiConfig.customParams },
            getIsEnabled = { DpiConfig.isEnabled }
        )
        socketFactory(dpiFactory)
        dns(DpiDns())
        protocols(listOf(Protocol.HTTP_1_1))
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return this
}
