package iad1tya.echo.music.dpi.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class DpiAutoProber {
    suspend fun findOptimalStrategy(
        onProgress: suspend (Int, Int, DpiStrategy) -> Unit
    ): DpiStrategy? = withContext(Dispatchers.IO) {
        val strategiesToTest = DpiStrategy.entries.toList()

        for ((index, strategy) in strategiesToTest.withIndex()) {
            onProgress(index + 1, strategiesToTest.size, strategy)
            
            if (testStrategy(strategy)) {
                return@withContext strategy
            }
        }
        
        null
    }

    private suspend fun testStrategy(strategy: DpiStrategy): Boolean = withContext(Dispatchers.IO) {
        try {
            val defaultFactory = SocketFactory.getDefault()
            val customSocketFactory = DpiSocketFactory(
                delegate = defaultFactory,
                getStrategy = { strategy },
                getCustomParams = { "" },
                getIsEnabled = { true }
            )
            
            val client = OkHttpClient.Builder()
                .socketFactory(customSocketFactory)
                .connectTimeout(8000, TimeUnit.MILLISECONDS)
                .readTimeout(8000, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                // Используем обычный www.youtube.com, он стабильнее для HEAD запросов, чем youtubei
                .url("https://www.youtube.com/")
                .head() // Use HEAD to minimize bandwidth during tests
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            
            // Если мы получили ЛЮБОЙ HTTP-ответ от сервера (даже 404), значит TLS Handshake прошел
            // и провайдер (ТСПУ) не сбросил соединение по SNI.
            return@withContext true
        } catch (e: Exception) {
            // Connection reset by peer, Timeout, SSLHandshakeException -> DPI сбросил/замедлил соединение
            android.util.Log.e("DpiAutoProber", "Strategy ${strategy.name} failed: ${e.message}")
            return@withContext false
        }
    }
}
