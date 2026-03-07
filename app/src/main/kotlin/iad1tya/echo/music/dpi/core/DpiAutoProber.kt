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
        val strategiesToTest = listOf(
            DpiStrategy.DEFAULT,
            DpiStrategy.SPLIT_1,
            DpiStrategy.SPLIT_2,
            DpiStrategy.OOB_INJECT,
            DpiStrategy.SNI_FRAG
        )

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
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                // youtubei.googleapis.com is an essential YT Music endpoint blocked by DPI in RF
                .url("https://youtubei.googleapis.com/generate_204")
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
            return@withContext false
        }
    }
}
