package iad1tya.echo.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.PlaybackException
import iad1tya.echo.music.constants.AudioQuality
import iad1tya.echo.music.constants.PlayerStreamClient
import com.echo.innertube.CloudflareDnsResolver
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
            .dns(CloudflareDnsResolver)
            .proxy(YouTube.proxy)
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
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        webClientPoTokenEnabled: Boolean = false,
        useVisitorData: Boolean = false,
        manualGvsPoToken: String? = null,
        manualPlayerPoToken: String? = null,
    ): Result<PlaybackData> = runCatching {
        coroutineScope {
            Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")

            // Detect uploaded/privately owned tracks (MLPT = My Library Personal Tracks sentinel).
            val isUploadedTrack = playlistId == "MLPT" || playlistId?.startsWith("MLPT") == true
            val apiPlaylistId: String? = if (isUploadedTrack) null else playlistId

            val isLoggedIn = YouTube.cookie != null
            val preferredClient = when (preferredStreamClient) {
                PlayerStreamClient.ANDROID_VR -> ANDROID_VR_NO_AUTH
                PlayerStreamClient.WEB_REMIX -> WEB_REMIX
                PlayerStreamClient.IOS -> IOS
                PlayerStreamClient.TVHTML5 -> TVHTML5
                PlayerStreamClient.ANDROID -> MOBILE
            }

            val sessionId = if (isLoggedIn && !useVisitorData) {
                YouTube.dataSyncId?.takeIf { it.isNotEmpty() } ?: YouTube.visitorData
            } else {
                YouTube.visitorData
            }

            val sigTimestampDeferred = async(Dispatchers.IO) {
                getSignatureTimestampOrNull(videoId)
            }

            val poTokenDeferred = async(Dispatchers.IO) {
                if (!manualGvsPoToken.isNullOrBlank() && !manualPlayerPoToken.isNullOrBlank()) {
                    PoTokenResult(playerRequestPoToken = manualPlayerPoToken, streamingDataPoToken = manualGvsPoToken)
                } else if (webClientPoTokenEnabled && MAIN_CLIENT.useWebPoTokens && sessionId != null) {
                    Timber.tag(logTag).d("Generating PoToken for ${MAIN_CLIENT.clientName}")
                    try {
                        poTokenGenerator.getWebClientPoToken(videoId, sessionId)
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

            val songPlaybackSource = databaseDao?.getSongById(currentVideoId)?.song?.playbackSource ?: 0
            val isErrorFallback = enableFallback && (mainPlayerResponse == null || mainPlayerResponse.playabilityStatus.status != "OK")
            
            val isForceRequestedFallback = if (songPlaybackSource == 2) {
                !isUploadedTrack && (mainPlayerResponse?.playabilityStatus?.status == "OK")
            } else {
                forceAllFallback && !isUploadedTrack && (mainPlayerResponse?.playabilityStatus?.status == "OK") && 
                mainPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_ATV"
            }

            if (isErrorFallback || isForceRequestedFallback) {
                val searchTitle = mainPlayerResponse?.videoDetails?.title
                val searchAuthor = mainPlayerResponse?.videoDetails?.author
                val searchQuery = if (searchTitle != null && searchAuthor != null) "$searchTitle - $searchAuthor" else (searchTitle ?: videoId)

                val fallbackVideoId = searchVideoId(searchQuery)
                if (fallbackVideoId != null && fallbackVideoId != currentVideoId) {
                    if (isErrorFallback) databaseDao?.upsert(SetVideoIdEntity(videoId, fallbackVideoId))
                    
                    return@coroutineScope playerResponseForPlayback(
                        videoId = fallbackVideoId,
                        playlistId = null,
                        audioQuality = audioQuality,
                        connectivityManager = connectivityManager,
                        enableFallback = false,
                        forceAllFallback = false,
                        databaseDao = databaseDao,
                        preferredStreamClient = preferredStreamClient,
                        webClientPoTokenEnabled = webClientPoTokenEnabled,
                        useVisitorData = useVisitorData,
                        manualGvsPoToken = manualGvsPoToken,
                        manualPlayerPoToken = manualPlayerPoToken
                    ).map { playbackData ->
                        playbackData.copy(videoDetails = mainPlayerResponse?.videoDetails ?: playbackData.videoDetails)
                    }.getOrThrow()
                }
            }

            val poToken: PoTokenResult? = poTokenDeferred.await()
            var response = mainPlayerResponse ?: mainPlayerResponseResult.getOrThrow()

            val mainStatus = response.playabilityStatus.status
            val isAgeRestricted = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

            if (isAgeRestricted && isLoggedIn) {
                YouTube.player(videoId, apiPlaylistId, WEB_CREATOR, null).getOrNull()?.let {
                    if (it.playabilityStatus.status == "OK") response = it
                }
            }

            val audioConfig = response.playerConfig?.audioConfig
            val videoDetails = response.videoDetails
            val playbackTracking = response.playbackTracking
            var format: PlayerResponse.StreamingData.Format? = null
            var streamUrl: String? = null
            var streamExpiresInSeconds: Int? = null
            var streamPlayerResponse: PlayerResponse? = null

            // Tracks the last successfully resolved stream (even if not validated).
            // Used as fallback when the last client(s) are skipped (loginRequired) or fail.
            var bestValidFormat: PlayerResponse.StreamingData.Format? = null
            var bestValidStreamUrl: String? = null
            var bestValidExpiry: Int? = null
            var bestValidStreamResponse: PlayerResponse? = null

            val isPrivateTrack = response.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" || isUploadedTrack
            val streamClients = buildList {
                add(preferredClient)
                add(MAIN_CLIENT)
                addAll(STREAM_FALLBACK_CLIENTS)
            }.distinctBy { it.clientName }

            for ((clientIndex, client) in streamClients.withIndex()) {
                format = null
                streamUrl = null
                streamExpiresInSeconds = null

                if (client.clientName == MAIN_CLIENT.clientName) {
                    streamPlayerResponse = response
                } else {
                    if (client.loginRequired && !isLoggedIn) continue
                    val clientSigTimestamp = if (isAgeRestricted) null else signatureTimestamp
                    val clientPoToken = if (webClientPoTokenEnabled && client.useWebPoTokens) {
                        poToken?.playerRequestPoToken?.takeIf { it.length >= 100 }
                    } else null
                    streamPlayerResponse = YouTube.player(videoId, apiPlaylistId, client, clientSigTimestamp, clientPoToken).getOrNull()
                }

                if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                    val responseToUse = if (isAgeRestricted || isPrivateTrack) streamPlayerResponse else {
                        YouTube.newPipePlayer(videoId, streamPlayerResponse!!) ?: streamPlayerResponse
                    }

                    format = findFormat(responseToUse!!, audioQuality, connectivityManager)
                    if (format == null) continue

                    val jsPlayerId = if (isPrivateTrack) (cachedPublicVideoId ?: videoId) else videoId
                    streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = isAgeRestricted, fallbackVideoId = jsPlayerId)
                    if (streamUrl == null) continue

                    val streamingToken = poToken?.streamingDataPoToken
                    if ((webClientPoTokenEnabled && (client.useWebPoTokens || isPrivateTrack)) && streamingToken != null && streamingToken.length >= 100) {
                        val separator = if ("?" in streamUrl!!) "&" else "?"
                        streamUrl = "${streamUrl}${separator}pot=${Uri.encode(streamingToken)}"
                    }

                    streamExpiresInSeconds = streamPlayerResponse?.streamingData?.expiresInSeconds
                    if (streamExpiresInSeconds == null) continue

                    // Persist as best-found fallback (in case later clients all fail/skip)
                    bestValidFormat = format
                    bestValidStreamUrl = streamUrl
                    bestValidExpiry = streamExpiresInSeconds
                    bestValidStreamResponse = streamPlayerResponse

                    val isPrivatelyOwned = streamPlayerResponse?.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK" || isUploadedTrack || isPrivateTrack
                    if (clientIndex == streamClients.size - 1 || isPrivatelyOwned) break
                    if (validateStatus(streamUrl!!)) break
                }
            }

            // If the loop finished without a break (e.g., all remaining clients loginRequired),
            // fall back to the last valid stream we found during the search.
            if (format == null && bestValidFormat != null) {
                format = bestValidFormat
                streamUrl = bestValidStreamUrl
                streamExpiresInSeconds = bestValidExpiry
                streamPlayerResponse = bestValidStreamResponse
            }

            if (streamPlayerResponse == null || streamPlayerResponse.playabilityStatus.status != "OK") {
                throw Exception("Bad stream player response - all clients failed")
            }

            if (format == null || streamUrl == null || streamExpiresInSeconds == null) {
                throw Exception("Failed to find stream data")
            }

            if (!isPrivateTrack) cachedPublicVideoId = videoId

            PlaybackData(
                audioConfig = audioConfig,
                videoDetails = videoDetails,
                playbackTracking = playbackTracking,
                format = format!!,
                streamUrl = streamUrl!!,
                streamExpiresInSeconds = streamExpiresInSeconds!!,
                playbackSource = if (isErrorFallback || currentVideoId != videoId) 2 else songPlaybackSource
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
        Timber.tag(logTag).d("Validating stream URL status")
        return try {
            val clientParam = url.substringAfter("?", "").split('&')
                .firstOrNull { it.startsWith("c=") }
                ?.substringAfter('=')

            val vClient = httpClient.newBuilder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
                .header("User-Agent", StreamClientUtils.resolveUserAgent(clientParam))

            val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)
            originReferer.origin?.let { requestBuilder.addHeader("Origin", it) }
            originReferer.referer?.let { requestBuilder.addHeader("Referer", it) }

            YouTube.cookie?.let { requestBuilder.addHeader("Cookie", it) }
            vClient.newCall(requestBuilder.build()).execute().use { it.isSuccessful }
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
