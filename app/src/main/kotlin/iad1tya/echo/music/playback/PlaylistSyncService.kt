package iad1tya.echo.music.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.echo.innertube.YouTube
import iad1tya.echo.music.R
import iad1tya.echo.music.MainActivity
import iad1tya.echo.music.db.MusicDatabase
import iad1tya.echo.music.db.entities.Playlist
import iad1tya.echo.music.db.entities.PlaylistSong
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.ArrayList
import javax.inject.Inject

@AndroidEntryPoint
class PlaylistSyncService : Service() {

    @Inject
    lateinit var database: MusicDatabase


    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationManager: NotificationManager? = null

    companion object {
        private const val CHANNEL_ID = "playlist_sync_channel"
        private const val NOTIFICATION_ID = 1001
        
        const val EXTRA_PLAYLIST_ID = "extra_playlist_id"
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
        const val EXTRA_SONG_IDS = "extra_song_ids"

        fun start(context: Context, playlistId: String, playlistName: String, songIds: ArrayList<String>) {
            val intent = Intent(context, PlaylistSyncService::class.java).apply {
                putExtra(EXTRA_PLAYLIST_ID, playlistId)
                putExtra(EXTRA_PLAYLIST_NAME, playlistName)
                putStringArrayListExtra(EXTRA_SONG_IDS, songIds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playlistId = intent?.getStringExtra(EXTRA_PLAYLIST_ID)
        val playlistName = intent?.getStringExtra(EXTRA_PLAYLIST_NAME) ?: "Playlist"
        val songIds = intent?.getStringArrayListExtra(EXTRA_SONG_IDS) ?: arrayListOf()

        if (playlistId == null || songIds.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                createNotification(playlistName, 0, songIds.size),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification(playlistName, 0, songIds.size))
        }

        serviceScope.launch {
            try {
                // 1. Create playlist
                val newBrowseId = try {
                    YouTube.createPlaylist(playlistName)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create playlist")
                    null
                }
                
                if (newBrowseId == null) {
                    updateNotification(playlistName, "Failed to create playlist on YouTube", 0, 0, true)
                    return@launch
                }

                // 2. Add songs in batches
                val batchSize = 50
                var syncedCount = 0
                for (i in 0 until songIds.size step batchSize) {
                    val batch = songIds.subList(i, minOf(i + batchSize, songIds.size))
                    val result = YouTube.addVideosToPlaylist(newBrowseId, batch)
                    
                    if (result.isSuccess) {
                        syncedCount += batch.size
                        updateNotification(playlistName, "Syncing: $syncedCount/${songIds.size}", syncedCount, songIds.size)
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Unknown error"
                        updateNotification(playlistName, "Batch error: $error. Continuing...", syncedCount, songIds.size)
                        Timber.e(result.exceptionOrNull(), "Failed to sync batch")
                    }
                }

                // 3. Update local database
                val playlistObj = database.playlist(playlistId).first()
                if (playlistObj != null) {
                    database.query {
                        update(playlistObj.playlist.copy(browseId = newBrowseId))
                    }
                }

                updateNotification(playlistName, "Sync completed: $syncedCount tracks", songIds.size, songIds.size, true)
            } catch (e: Exception) {
                Timber.e(e, "Error syncing playlist")
                updateNotification(playlistName, "Error: ${e.localizedMessage}", 0, 0, true)
            } finally {
                delay(5000) // Keep the completed/error notification for 5s
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Playlist Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        title: String,
        progress: Int,
        max: Int,
        isFinished: Boolean = false
    ): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.backup)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(!isFinished)
            .setContentIntent(pendingIntent)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }

        return builder.build()
    }

    private fun updateNotification(
        title: String,
        content: String,
        progress: Int,
        max: Int,
        isFinished: Boolean = false
    ) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.backup)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(!isFinished)
            .setContentIntent(pendingIntent)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }

        notificationManager?.notify(NOTIFICATION_ID, builder.build())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
