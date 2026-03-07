package iad1tya.echo.music.dpi.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

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

fun getDefaultTrustManager(): javax.net.ssl.X509TrustManager {
    val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as java.security.KeyStore?)
    return factory.trustManagers.first { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager
}

fun okhttp3.OkHttpClient.Builder.applyDpi(): okhttp3.OkHttpClient.Builder {
    try {
        val trustManager = getDefaultTrustManager()
        val defaultFactory = javax.net.ssl.SSLContext.getDefault().socketFactory as javax.net.ssl.SSLSocketFactory
        val dpiFactory = DpiSocketFactory(
            delegate = defaultFactory,
            getStrategy = { DpiConfig.currentStrategy },
            getCustomParams = { DpiConfig.customParams },
            getIsEnabled = { DpiConfig.isEnabled }
        )
        sslSocketFactory(dpiFactory, trustManager)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return this
}
