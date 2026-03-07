package iad1tya.echo.music.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import iad1tya.echo.music.LocalDatabase
import iad1tya.echo.music.LocalPlayerConnection
import iad1tya.echo.music.R
import iad1tya.echo.music.db.entities.PlaylistEntity
import iad1tya.echo.music.db.entities.PlaylistSongMap
import iad1tya.echo.music.models.toMediaMetadata
import iad1tya.echo.music.ui.component.IconButton
import iad1tya.echo.music.ui.utils.backToMain
import iad1tya.echo.music.utils.SpotifyImportHelper
import iad1tya.echo.music.utils.YandexImportHelper
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YandexImportScreen(
    navController: NavController,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()

    var yandexUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var importedSongs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var playlistName by remember { mutableStateOf("") }
    var importProgress by remember { mutableIntStateOf(0) }
    var totalTracks by remember { mutableIntStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Yandex Import",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily(Font(R.font.zalando_sans_expanded)),
                            fontWeight = FontWeight.Bold
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // URL input
            OutlinedTextField(
                value = yandexUrl,
                onValueChange = { yandexUrl = it },
                label = { Text("Yandex Playlist URL") },
                placeholder = { Text("https://music.yandex.ru/users/.../playlists/...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Fetch button
            Button(
                onClick = {
                    if (yandexUrl.isBlank()) {
                        Toast.makeText(context, "Please enter a Yandex Music playlist URL", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    scope.launch {
                        isLoading = true
                        statusText = "Fetching Yandex playlist..."
                        try {
                            val (name, songs) = YandexImportHelper.getPlaylistSongs(yandexUrl)
                            playlistName = name
                            importedSongs = songs
                            totalTracks = songs.size
                            statusText = if (songs.isEmpty()) {
                                "No songs found or playlist is private. Check the URL."
                            } else {
                                "Found $totalTracks tracks in \"$name\""
                            }
                        } catch (e: Exception) {
                            statusText = "Error: ${e.message}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && !isImporting,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Fetch Playlist")
            }

            // Import button
            if (importedSongs.isNotEmpty()) {
                Button(
                    onClick = {
                        scope.launch {
                            isImporting = true
                            importProgress = 0
                            statusText = "Matching tracks with YouTube..."

                            val foundIds = mutableListOf<String>()
                            val failed = mutableListOf<String>()

                            for ((index, pair) in importedSongs.withIndex()) {
                                val (title, artist) = pair
                                importProgress = index + 1
                                statusText = "Searching: $title ($importProgress/$totalTracks)"

                                // Reuse Spotify's YouTube search logic as it's identical
                                val videoId = SpotifyImportHelper.searchYouTubeForSong(title, artist)
                                if (videoId != null) {
                                    foundIds.add(videoId)
                                } else {
                                    failed.add("$title - $artist")
                                }
                            }

                            // Create playlist with found songs
                            if (foundIds.isNotEmpty()) {
                                withContext(Dispatchers.IO) {
                                    val songMetadataList = foundIds.mapNotNull { songId ->
                                        try {
                                            com.echo.innertube.YouTube.queue(listOf(songId))
                                                .getOrNull()?.firstOrNull()?.let { ytSong ->
                                                    songId to ytSong.toMediaMetadata()
                                                }
                                        } catch (_: Exception) { null }
                                    }
                                    
                                    database.query {
                                        val playlist = PlaylistEntity(
                                            name = playlistName,
                                            browseId = null,
                                            bookmarkedAt = LocalDateTime.now(),
                                            isEditable = true,
                                        )
                                        insert(playlist)
                                        songMetadataList.forEachIndexed { idx, (songId, metadata) ->
                                            insert(metadata)
                                            insert(
                                                PlaylistSongMap(
                                                    songId = songId,
                                                    playlistId = playlist.id,
                                                    position = idx
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            statusText = "Done! Imported ${foundIds.size}/$totalTracks songs" +
                                    if (failed.isNotEmpty()) ". ${failed.size} not found." else ""
                            isImporting = false

                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context,
                                    "Playlist \"$playlistName\" created with ${foundIds.size} songs",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImporting && !isLoading,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Import to Echo Music")
                }

                if (isImporting) {
                    LinearProgressIndicator(
                        progress = { importProgress.toFloat() / totalTracks.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status
            if (statusText.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Song list preview
            if (importedSongs.isNotEmpty()) {
                Text(
                    text = "Tracks ($totalTracks)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(importedSongs) { index, (title, artist) ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            leadingContent = {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
