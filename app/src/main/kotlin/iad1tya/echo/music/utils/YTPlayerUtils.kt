package iad1tya.echo.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.PlaybackException
import iad1tya.echo.music.constants.AudioQuality
import com.echo.innertube.NewPipeUtils
import com.echo.innertube.YouTube
import com.echo.innertube.models.YouTubeClient
import com.echo.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.echo.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.echo.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.echo.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.echo.innertube.models.YouTubeClient.Companion.IOS
import com.echo.innertube.models.YouTubeClient.Companion.IPADOS
import com.echo.innertube.models.YouTubeClient.Companion.MOBILE
import com.echo.innertube.models.YouTubeClient.Companion.TVHTML5
import com.echo.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.echo.innertube.models.YouTubeClient.Companion.WEB
import com.echo.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.echo.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.echo.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import iad1tya.echo.music.db.DatabaseDao
import iad1tya.echo.music.db.entities.SetVideoIdEntity
import timber.log.Timber
import iad1tya.echo.music.utils.potoken.PoTokenGenerator
import iad1tya.echo.music.utils.potoken.PoTokenResult
import kotlinx.coroutines.*

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"

    /**
     * Cached videoId of the last successfully-played PUBLIC (non-private) video.
     * Used as a JS player fallback when resolving signature timestamps / URL deobfuscation
     * for privately-owned/uploaded tracks, which cannot be fetched by NewPipe directly.
     */
    @Volatile private var cachedPublicVideoId: String? = null

    private val poTokenGenerator = PoTokenGenerator()

    private val httpClient = iad1tya.echo.music.dpi.core.DpiConfig.applyTo(
        OkHttpClient.Builder()
            .apply {
                YouTube.proxy?.let { proxy(it) }
            }
    ).build()
    /**
     * The main client is used for metadata and initial streams.
     * [WEB_REMIX] provides correct metadata (loudnessDb), premium formats,
     * and works for most content including proper history tracking.
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     * Order matches Metrolist for maximum compatibility:
     * - TVHTML5_SIMPLY_EMBEDDED_PLAYER first for age-restricted content
     * - Then various client fallbacks
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val playbackSource: Int = 0,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     * Includes: uploaded track handling, age-restricted content, NewPipe stream enrichment.
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        enableFallback: Boolean = true,
        forceAllFallback: Boolean = false,
        databaseDao: DatabaseDao? = null,
    ): Result<PlaybackData> = runCatching {
        coroutineScope {
            Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")

            // Detect uploaded/privately owned tracks (MLPT = My Library Personal Tracks sentinel).
            // "MLPT" is Echo-internal — never forward it to the YouTube API.
            val isUploadedTrack = playlistId == "MLPT" || playlistId?.startsWith("MLPT") == true
            // Real YouTube playlist ID to pass to the API — null for uploaded tracks so YouTube
            // doesn't reject the request with "this video is not available".
            val apiPlaylistId: String? = if (isUploadedTrack) null else playlistId
            if (isUploadedTrack) {
                Timber.tag(logTag).d("Detected uploaded track (MLPT sentinel) — apiPlaylistId=null")
            }

            val isLoggedIn = YouTube.cookie != null
            val sessionId = if (isLoggedIn) {
                YouTube.dataSyncId?.takeIf { it.isNotEmpty() } ?: YouTube.visitorData
            } else {
                YouTube.visitorData
            }

            val sigTimestampDeferred = async(Dispatchers.IO) {
                getSignatureTimestampOrNull(videoId)
            }
            val poTokenDeferred = async(Dispatchers.IO) {
                if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
                    Timber.tag(logTag).d("Generating PoToken for ${MAIN_CLIENT.clientName}")
                    try {
                        val pt = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                        if (pt != null) {
                            Timber.tag(logTag).d("PoToken generated successfully")
                        }
                        pt
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
                        null
                    }
                } else null
            }

            val signatureTimestamp = sigTimestampDeferred.await()
                ?: cachedPublicVideoId?.let { fallbackId ->
                    Timber.tag(logTag).d("Sig timestamp failed for $videoId, retrying with cached public video: $fallbackId")
                    getSignatureTimestampOrNull(fallbackId)
                }
            Timber.tag(logTag).d("Signature timestamp: $signatureTimestamp")

            var currentVideoId = videoId
            if (enableFallback && databaseDao != null) {
                val cachedFallback = databaseDao.getSetVideoId(videoId)
                if (cachedFallback != null && cachedFallback.setVideoId != null) {
                    currentVideoId = cachedFallback.setVideoId!!
                    Timber.tag(logTag).d("Using cached fallback videoId: $currentVideoId for original: $videoId")
                }
            }

            var mainPlayerResponseResult = YouTube.player(currentVideoId, apiPlaylistId, MAIN_CLIENT, signatureTimestamp, null)
            var mainPlayerResponse = mainPlayerResponseResult.getOrNull()

            // Per-song source preference: 0=AUTO, 1=YTM, 2=YT
            val songPlaybackSource = databaseDao?.getSongById(currentVideoId)?.song?.playbackSource ?: 0
            
            // Handle fallbacks: either error (geo-restriction/unavailability) OR experimental "all songs" fallback
            val isErrorFallback = enableFallback && (mainPlayerResponse == null || mainPlayerResponse.playabilityStatus.status != "OK")
            
            // Should we force YouTube fallback? Either global setting OR per-song preference
            // But respect YTM preference if explicitly set (1)
            val isForceYoutubeRequested = (forceAllFallback || songPlaybackSource == 2) && songPlaybackSource != 1
            
            // If YouTube source is explicitly requested (songPlaybackSource == 2), we always allow fallback search
            // even if it's already a video (to find a better/official video if it's currently an ATV/Audio track).
            // For global 'forceAllFallback', we keep the ATV restriction to avoid unnecessary searches for everything.
            val isForceRequestedFallback = if (songPlaybackSource == 2) {
                !isUploadedTrack && (mainPlayerResponse?.playabilityStatus?.status == "OK")
            } else {
                forceAllFallback && !isUploadedTrack && (mainPlayerResponse?.playabilityStatus?.status == "OK") && 
                mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
            }

            if (isErrorFallback || isForceRequestedFallback) {
                if (isForceRequestedFallback) {
                    Timber.tag(logTag).d("YouTube All Fallback (experimental) requested for $currentVideoId. Attempting fallback search.")
                } else {
                    val status = mainPlayerResponse?.playabilityStatus?.status
                    val reason = mainPlayerResponse?.playabilityStatus?.reason
                    Timber.tag(logTag).d("Track $currentVideoId failed with status $status ($reason). Attempting fallback search.")
                }

                val searchTitle = mainPlayerResponse?.videoDetails?.title
                val searchAuthor = mainPlayerResponse?.videoDetails?.author
                val searchQuery = if (searchTitle != null && searchAuthor != null) {
                    "$searchTitle - $searchAuthor"
                } else {
                    searchTitle ?: videoId
                }

                val fallbackVideoId = searchVideoId(searchQuery)
                if (fallbackVideoId != null && fallbackVideoId != currentVideoId) {
                    Timber.tag(logTag).d("Found fallback videoId: $fallbackVideoId for query: $searchQuery. Retrying playback.")
                    
                    // Save to database for future "silent" fallback (only for error-based fallback)
                    if (isErrorFallback) {
                        databaseDao?.upsert(SetVideoIdEntity(videoId, fallbackVideoId))
                    }

                    return@coroutineScope playerResponseForPlayback(
                        videoId = fallbackVideoId,
                        playlistId = null,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                        enableFallback = false,
                        forceAllFallback = false,
                        databaseDao = databaseDao
                    ).map { playbackData ->
                        playbackData.copy(
                            videoDetails = mainPlayerResponse?.videoDetails ?: playbackData.videoDetails
                        )
                    }.getOrThrow()
                } else {
                    if (isErrorFallback) {
                        Timber.tag(logTag).e("No fallback video found for $videoId")
                        // If we can't find a fallback, we must throw the original error if it's a real failure
                        if (mainPlayerResponse == null) {
                            mainPlayerResponseResult.getOrThrow()
                        }
                    } else {
                        Timber.tag(logTag).d("No better fallback found for $videoId, using the original.")
                    }
                }
            }
            
            // Re-assign mainPlayerResponse if it was null but we didn't return (shouldn't happen with getOrThrow)
            if (mainPlayerResponse == null) mainPlayerResponse = mainPlayerResponseResult.getOrThrow()

            val poToken: PoTokenResult? = poTokenDeferred.await()

            // Check for age-restricted content
            val mainStatus = mainPlayerResponse.playabilityStatus.status
            val isAgeRestricted = mainStatus in listOf(
                "AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED",
                "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED"
            )

        if (isAgeRestricted && isLoggedIn) {
            Timber.tag(logTag).d("Age-restricted detected, trying WEB_CREATOR")
            val creatorResponse = YouTube.player(videoId, apiPlaylistId, WEB_CREATOR, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
            }
        }

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null

        // Detect privately owned track from response metadata
        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType ==
            "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" || isUploadedTrack

        // Check if main client response failed (not OK and not age-restricted)
        val mainClientFailed = mainPlayerResponse.playabilityStatus.status != "OK" && !isAgeRestricted

        // Determine starting client index (matches Metrolist's logic exactly):
        // - Private/uploaded tracks: ALWAYS start at TVHTML5 (index 1).
        //   WEB_REMIX may return OK metadata but its streams don't work for uploaded tracks;
        //   TVHTML5 with a valid cookie is the only client that streams them reliably.
        // - Age-restricted: start at index 0 (TVHTML5_SIMPLY_EMBEDDED_PLAYER)
        // - Main client failed for non-private: start at index 0 to try all fallback clients
        // - Normal content: start at -1 (use WEB_REMIX streams first)
        val startIndex = when {
            isPrivateTrack && !mainClientFailed -> -1  // WEB_REMIX OK: try its streams first
            isPrivateTrack && mainClientFailed -> 1    // WEB_REMIX failed: skip to TVHTML5
            isAgeRestricted -> 0      // Skip main client
            mainClientFailed -> 0     // Try all fallback clients
            else -> -1
        }

        if (isPrivateTrack) {
            Timber.tag(logTag).d("Private/uploaded track: trying WEB_REMIX streams first (with pot=)")
        }
        if (mainClientFailed && !isPrivateTrack) {
            Timber.tag(logTag).d("Main client returned status '${mainPlayerResponse.playabilityStatus.status}': ${mainPlayerResponse.playabilityStatus.reason}, trying fallback clients")
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client: YouTubeClient
            if (clientIndex == -1) {
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                // Skip signature timestamp for age-restricted content (faster)
                val clientSigTimestamp = if (isAgeRestricted) null else signatureTimestamp
                // Only pass poToken to clients that support it AND when we have a valid-sized token.
                // An 88-byte token (too small) causes YouTube to reject requests differently than no token.
                // Valid PoTokens are base64-encoded 110-128 byte arrays (~148-172 base64 chars).
                val clientPoToken = if (client.useWebPoTokens) {
                    val pt = poToken?.playerRequestPoToken
                    if (pt != null && pt.length >= 100) pt else null
                } else null
                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                streamPlayerResponse =
                    YouTube.player(videoId, apiPlaylistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Try NewPipe stream enrichment (deobfuscated URLs)
                // Skip for age-restricted (no auth) and private tracks (NewPipe can't access private videos)
                val responseToUse = if (isAgeRestricted || isPrivateTrack) {
                    if (isAgeRestricted) Timber.tag(logTag).d("Skipping NewPipe enrichment for age-restricted content")
                    if (isPrivateTrack) Timber.tag(logTag).d("Skipping NewPipe enrichment for private/uploaded track")
                    streamPlayerResponse
                } else {
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse!!)
                    newPipeResponse ?: streamPlayerResponse
                }

                format = findFormat(
                    responseToUse!!,
                    audioQuality,
                    connectivityManager,
                )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                // For private tracks, use cached public videoId for JS player operations
                // (private video pages can't be fetched by NewPipe for sig deobfuscation)
                val jsPlayerId = if (isPrivateTrack) (cachedPublicVideoId ?: videoId) else videoId
                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = isAgeRestricted, fallbackVideoId = jsPlayerId)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                // Append pot= streaming data token for web-based clients or private tracks.
                // YouTube CDN requires this token on the stream URL for web/TV clients in 2026.
                // Only append if the streaming token is valid size (>= 100 base64 chars).
                val currentClient = if (clientIndex == -1) MAIN_CLIENT else STREAM_FALLBACK_CLIENTS[clientIndex]
                val streamingToken = poToken?.streamingDataPoToken
                if ((currentClient.useWebPoTokens || isPrivateTrack) &&
                    streamingToken != null && streamingToken.length >= 100) {
                    Timber.tag(logTag).d("Appending pot= parameter to stream URL for ${currentClient.clientName}")
                    val separator = if ("?" in streamUrl!!) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${Uri.encode(streamingToken)}"
                }

                streamExpiresInSeconds = streamPlayerResponse?.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Skip validation for privately owned / uploaded tracks.
                // isPrivateTrack is detected from WEB_REMIX (main client) which reliably returns
                // "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK". Fallback clients like TVHTML5 are
                // generic YouTube TV clients that do NOT include this YouTube-Music-specific
                // field in their response, so checking only streamPlayerResponse.musicVideoType
                // would be false — causing validateStatus() to run, which fails because private
                // CDN stream URLs don't respond to unauthenticated HEAD requests, and the loop
                // then continues past the valid TVHTML5 stream to eventual WEB_CREATOR failure.
                val isPrivatelyOwned = streamPlayerResponse?.videoDetails?.musicVideoType ==
                    "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" || isUploadedTrack || isPrivateTrack
                if (clientIndex == -1 || clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    if (isPrivatelyOwned) {
                        Timber.tag(logTag).d("Skipping validation for privately owned/uploaded track")
                    } else if (clientIndex == -1) {
                        Timber.tag(logTag).d("Using MAIN_CLIENT without extra validation for speed")
                    } else {
                        Timber.tag(logTag).d("Using last fallback client without validation")
                    }
                    break
                }

                // Only validate for fallback clients that are not the last one
                if (validateStatus(streamUrl)) {
                    Timber.tag(logTag).d("Stream validated successfully")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed, trying next client")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        // Cache this public video's ID for future private track JS player lookups
        if (!isPrivateTrack) {
            cachedPublicVideoId = videoId
            Timber.tag(logTag).d("Cached public videoId: $videoId for future private track resolution")
        }
            PlaybackData(
                audioConfig = audioConfig,
                videoDetails = videoDetails,
                playbackTracking = playbackTracking,
                format = format!!,
                streamUrl = streamUrl!!,
                streamExpiresInSeconds = streamExpiresInSeconds!!,
                playbackSource = if (isErrorFallback || fallbackVideoId != null) 2 else songPlaybackSource
            )
        }
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using WEB_REMIX")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * Adds authentication cookie for privately owned/uploaded tracks.
     */
    private fun validateStatus(url: String): Boolean {
        return try {
            val client = httpClient.newBuilder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
            YouTube.cookie?.let { requestBuilder.addHeader("Cookie", it) }
            client.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).d("Signature timestamp obtained: $it") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get signature timestamp")
                reportException(it)
            }
            .getOrNull()
    }
    /**
     * Multi-strategy URL resolution for stream formats:
     * 1. Direct URL from format (already deobfuscated by NewPipe enrichment)
     * 2. NewPipe signature deobfuscation (cipher formats)
     * 3. StreamInfo full extraction fallback (NewPipe's complete pipeline)
     */
    /**
     * Resolves a playable stream URL from a format, always applying the YouTube n-transform
     * (throttle parameter deobfuscation) to prevent CDN rejection.
     *
     * For private/uploaded tracks, the TVHTML5 client returns formats with direct `url` fields.
     * These URLs contain an unobfuscated `n` parameter — without transforming it, YouTube's CDN
     * throttles/rejects the stream. We always run through [NewPipeUtils.getStreamUrl] which calls
     * [YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated].
     *
     * For private videoIds, the NewPipe player manager can't fetch their video page, so we use
     * [fallbackVideoId] (a recently-played public video) to look up the shared JS player.
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false,
        fallbackVideoId: String? = null,
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")

        // Step 1: Direct URL — apply n-transform to prevent CDN throttling, then return.
        // YouTube CDN throttles streams whose `n` parameter hasn't been deobfuscated.
        // For private/uploaded tracks, the private videoId's page can't be fetched by NewPipe,
        // so we use the fallbackVideoId (a recently-played public video) whose JS player is cached.
        // If n-transform fails or is unavailable, fall back to the raw direct URL (may be throttled).
        if (!format.url.isNullOrEmpty()) {
            val jsPlayerId = fallbackVideoId ?: videoId
            val ntransformedUrl = NewPipeUtils.getStreamUrl(format, jsPlayerId).getOrNull()
            if (ntransformedUrl != null) {
                Timber.tag(logTag).d("Direct URL with n-transform applied (jsPlayerId=$jsPlayerId)")
                return ntransformedUrl
            }
            // n-transform failed (JS player not cached or jsPlayer unavailable for videoId) — use raw URL
            Timber.tag(logTag).d("N-transform unavailable, using raw direct URL (may be CDN-throttled)")
            return format.url
        }

        // Step 2: Cipher URL — must deobfuscate signature (and n-param) via NewPipe.
        // Age-restricted content skips NewPipe (no auth context available via NewPipe).
        if (skipNewPipe) {
            Timber.tag(logTag).d("Cipher format but skipNewPipe=true for age-restricted content")
            return null
        }

        // Try primary videoId (public videos cache the JS player after first run).
        val primaryUrl = NewPipeUtils.getStreamUrl(format, videoId)
            .onSuccess { Timber.tag(logTag).d("Stream URL from NewPipe (primary $videoId)") }
            .onFailure { Timber.tag(logTag).d("NewPipe primary failed for $videoId: ${it.message}") }
            .getOrNull()
        if (primaryUrl != null) return primaryUrl

        // For private/uploaded videoIds NewPipe can't fetch their page — retry with a public fallback
        // that shares the same JS player version (deobfuscation functions are per-player, not per-video).
        if (fallbackVideoId != null && fallbackVideoId != videoId) {
            Timber.tag(logTag).d("Retrying NewPipe cipher deobfuscation with fallback videoId: $fallbackVideoId")
            val fallbackUrl = NewPipeUtils.getStreamUrl(format, fallbackVideoId)
                .onSuccess { Timber.tag(logTag).d("Stream URL from NewPipe (fallback $fallbackVideoId)") }
                .onFailure { Timber.tag(logTag).d("NewPipe fallback also failed: ${it.message}") }
                .getOrNull()
            if (fallbackUrl != null) return fallbackUrl
        }

        Timber.tag(logTag).e("All URL resolution methods failed for videoId=$videoId")
        return null
    }

    /**
     * Searches for a video matching the query and returns the first videoId.
     */
    private suspend fun searchVideoId(query: String): String? {
        Timber.tag(logTag).d("Searching for fallback video with query: $query")
        return YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()?.items
            ?.firstOrNull()?.id
    }

    /**
     * Force refresh decryption/stream caches for a video.
     * Called on playback errors to ensure fresh streams on retry.
     */
    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing caches for videoId: $videoId")
        // NewPipe manages its own JS player cache internally.
        // This method exists so error handlers can call it consistently.
    }
}
