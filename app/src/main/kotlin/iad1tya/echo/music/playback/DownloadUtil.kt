package iad1tya.echo.music.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.echo.innertube.YouTube
import iad1tya.echo.music.constants.AudioQuality
import iad1tya.echo.music.constants.AudioQualityKey
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.FormatEntity
import iad1tya.echo.music.db.entities.SongEntity
import iad1tya.echo.music.di.DownloadCache
import iad1tya.echo.music.di.PlayerCache
import iad1tya.echo.music.utils.YTPlayerUtils
import iad1tya.echo.music.utils.enumPreference
import iad1tya.echo.music.utils.preference
import iad1tya.echo.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.echo.innertube.YouTube
import iad1tya.echo.music.constants.AudioQuality
import iad1tya.echo.music.constants.AudioQualityKey
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.FormatEntity
import iad1tya.echo.music.db.entities.SongEntity
import iad1tya.echo.music.di.DownloadCache
import iad1tya.echo.music.di.PlayerCache
import iad1tya.echo.music.utils.YTPlayerUtils
import iad1tya.echo.music.utils.enumPreference
import iad1tya.echo.music.utils.preference
import iad1tya.echo.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val audioQuality by enumPreference(context, AudioQualityKey, AudioQuality.AUTO)
    private val youtubeVideoFallbackEnabled by preference(context, iad1tya.echo.music.constants.YoutubeVideoFallbackKey, true)
    private val youtubeAllFallbackEnabled by preference(context, iad1tya.echo.music.constants.YoutubeAllFallbackKey, false)
    private val songUrlCache = HashMap<String, Pair<String, Long>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(playerCache)
                .setUpstreamDataSourceFactory(
                    OkHttpDataSource.Factory(
                        iad1tya.echo.music.dpi.core.DpiConfig.applyTo(OkHttpClient.Builder(), context)
                            .proxy(YouTube.proxy)
                            .proxyAuthenticator { _, response ->
                                YouTube.proxyAuth?.let { auth ->
                                    response.request.newBuilder()
                                        .header("Proxy-Authorization", auth)
                                        .build()
                                } ?: response.request
                            }
                            .build(),
                    ),
                ),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")
            val length = if (dataSpec.length >= 0) dataSpec.length else 1

            val cachedUrl = songUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }

            if (cachedUrl != null && playerCache.isCached(mediaId, dataSpec.position, length)) {
                return@Factory dataSpec
            }

            cachedUrl?.let {
                return@Factory dataSpec.withUri(it.first.toUri())
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                    enableFallback = youtubeVideoFallbackEnabled,
                    forceAllFallback = youtubeAllFallbackEnabled,
                    databaseDao = database
                )
            }.getOrThrow()
            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.split("codecs=")[1].removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength!!,
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongEntityByIdBlocking(mediaId)

                val updatedSong = if (existing != null) {
                    existing.copy(
                        dateDownload = existing.dateDownload ?: now,
                        playbackSource = playbackData.playbackSource
                    )
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                        dateDownload = now,
                        isDownloaded = existing?.isDownloaded ?: false,
                        playbackSource = playbackData.playbackSource
                    )
                }

                upsert(updatedSong)
            }

            val streamUrl = playbackData.streamUrl.let {
                "${it}&range=0-${format.contentLength ?: 10000000}"
            }

            songUrlCache[mediaId] = streamUrl to (System.currentTimeMillis() + playbackData.streamExpiresInSeconds * 1000L)
            dataSpec.withUri(streamUrl.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Dispatchers.IO.asExecutor()
        ).apply {
            maxParallelDownloads = 6
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                remove(download.request.id)
                            }
                        }
                        scope.launch {
                            try {
                                val ok = downloadCache.removeResource(download.request.id)
                                android.util.Log.d("DownloadUtil", "Removed resource ${download.request.id}: $ok")
                            } catch (e: Exception) {
                                android.util.Log.e("DownloadUtil", "Failed to remove resource ${download.request.id}", e)
                            }
                            database.updateDownloadedInfo(download.request.id, false, null)
                        }
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    if (downloadCache.isCached(download.request.id, 0, C.LENGTH_UNSET)) {
                                        database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    } else {
                                        android.util.Log.w("DownloadUtil", "Download completed but resource not in cache: ${download.request.id}")
                                        database.updateDownloadedInfo(download.request.id, false, null)
                                    }
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING,
                                Download.STATE_RESTARTING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun removeFromCache(songId: String) {
        songUrlCache.remove(songId)
        try {
            playerCache.removeResource(songId)
        } catch (e: Exception) {
            // Ignore if not in cache or if it fails
        }
    }

    fun release() {
        scope.cancel()
    }
}
