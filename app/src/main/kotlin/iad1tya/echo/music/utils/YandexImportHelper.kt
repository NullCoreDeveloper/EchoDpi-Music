package iad1tya.echo.music.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

object YandexImportHelper {
    private const val TAG = "YandexImportHelper"
    private val gson = Gson()
    
    // Use a persistent client with cookie support to mimic the Python session
    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }
    
    private val client = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Extracts tracks from a Yandex Music playlist URL.
     * Ported from Python test.py logic.
     */
    suspend fun getPlaylistSongs(url: String): Pair<String, List<Pair<String, String>>> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Pair<String, String>>()
        var playlistName = "Yandex Import"

        try {
            val (username, playlistId) = extractOwnerAndKind(url) ?: return@withContext playlistName to emptyList()

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                "Referer" to "https://music.yandex.ru/users/$username/playlists/$playlistId",
                "X-Requested-With" to "XMLHttpRequest"
            )

            // Step 1: Visit the page to get cookies (like session.get(page_url) in python)
            val pageRequest = Request.Builder()
                .url("https://music.yandex.ru/users/$username/playlists/$playlistId")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            
            client.newCall(pageRequest).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "Initial page visit failed: ${response.code}")
            }

            // Step 2: Call the handlers API
            val apiUrl = "https://music.yandex.ru/handlers/playlist.jsx?owner=$username&kinds=$playlistId"
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()

            client.newCall(apiRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext playlistName to emptyList()
                    val data = gson.fromJson(body, JsonObject::class.java)
                    
                    val playlistObj = data.getAsJsonObject("playlist") ?: return@withContext playlistName to emptyList()
                    playlistName = playlistObj.get("title")?.asString ?: playlistName
                    
                    val tracksArray = playlistObj.getAsJsonArray("tracks") ?: return@withContext playlistName to emptyList()
                    
                    for (element in tracksArray) {
                        val trackObj = element.asJsonObject
                        // Sometimes track data is nested
                        val innerTrack = if (trackObj.has("track")) trackObj.getAsJsonObject("track") else trackObj
                        
                        val title = innerTrack.get("title")?.asString ?: continue
                        val version = if (innerTrack.has("version")) innerTrack.get("version")?.asString else null
                        val fullTitle = if (version != null) "$title ($version)" else title
                        
                        val artistsArray = innerTrack.getAsJsonArray("artists")
                        val artistsStr = artistsArray?.joinToString(", ") { 
                            it.asJsonObject.get("name")?.asString ?: "" 
                        } ?: ""
                        
                        songs.add(fullTitle to artistsStr)
                    }
                } else {
                    Log.e(TAG, "API call failed: ${response.code}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error importing Yandex playlist", e)
        }

        playlistName to songs
    }

    private fun extractOwnerAndKind(url: String): Pair<String, String>? {
        // Formats: 
        // https://music.yandex.ru/users/user/playlists/3
        // music.yandex.ru/users/user/playlists/3
        val regex = Regex("users/([^/]+)/playlists/(\\d+)")
        val match = regex.find(url)
        return if (match != null) {
            match.groupValues[1] to match.groupValues[2]
        } else null
    }
}
