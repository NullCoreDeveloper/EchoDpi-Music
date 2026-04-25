import sys

path = '/home/nethunter/Documents/EchoDpi-Music/app/src/main/kotlin/iad1tya/echo/music/utils/YTPlayerUtils.kt'
with open(path, 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
in_conflict = False

for line in lines:
    if line.strip() == '<<<<<<< HEAD':
        in_conflict = True
        skip = True
        # Logic to decide which conflict it is
        continue
    
    if line.strip() == '=======' and in_conflict:
        continue

    if line.strip().startswith('>>>>>>> upstream/main') and in_conflict:
        skip = False
        in_conflict = False
        continue

    if not skip:
        new_lines.append(line)
        continue

    # We are in skip mode (conflict resolution)
    if 'iad1tya.echo.music.dpi.core.DpiConfig.applyTo' in line:
        # Conflict 1: httpClient
        new_lines.append('    private val httpClient = iad1tya.echo.music.dpi.core.DpiConfig.applyTo(\n')
        new_lines.append('        OkHttpClient.Builder()\n')
        new_lines.append('            .dns(CloudflareDnsResolver)\n')
        new_lines.append('            .apply {\n')
        new_lines.append('                YouTube.proxy?.let { proxy(it) }\n')
        new_lines.append('            }\n')
        new_lines.append('    ).build()\n')
        skip = False
        in_conflict = False
    elif 'enableFallback: Boolean = true' in line:
        # Conflict 2: playerResponseForPlayback signature
        new_lines.append('        enableFallback: Boolean = true,\n')
        new_lines.append('        forceAllFallback: Boolean = false,\n')
        new_lines.append('        databaseDao: DatabaseDao? = null,\n')
        new_lines.append('        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,\n')
        new_lines.append('        webClientPoTokenEnabled: Boolean = false,\n')
        new_lines.append('        useVisitorData: Boolean = false,\n')
        new_lines.append('        manualGvsPoToken: String? = null,\n')
        new_lines.append('        manualPlayerPoToken: String? = null,\n')
        skip = False
        in_conflict = False
    elif 'val sigTimestampDeferred = async(Dispatchers.IO) {' in line:
        # Conflict 3: implementation of playerResponseForPlayback
        # This is big. I'll just write the merged logic.
        new_lines.append('            val isLoggedIn = YouTube.cookie != null\n')
        new_lines.append('            val preferredClient = when (preferredStreamClient) {\n')
        new_lines.append('                PlayerStreamClient.ANDROID_VR -> ANDROID_VR_NO_AUTH\n')
        new_lines.append('                PlayerStreamClient.WEB_REMIX -> WEB_REMIX\n')
        new_lines.append('                PlayerStreamClient.IOS -> IOS\n')
        new_lines.append('                PlayerStreamClient.TVHTML5 -> TVHTML5\n')
        new_lines.append('                PlayerStreamClient.ANDROID -> MOBILE\n')
        new_lines.append('            }\n')
        new_lines.append('\n')
        new_lines.append('            val sessionId = if (isLoggedIn && !useVisitorData) {\n')
        new_lines.append('                YouTube.dataSyncId?.takeIf { it.isNotEmpty() } ?: YouTube.visitorData\n')
        new_lines.append('            } else {\n')
        new_lines.append('                YouTube.visitorData\n')
        new_lines.append('            }\n')
        new_lines.append('\n')
        new_lines.append('            val sigTimestampDeferred = async(Dispatchers.IO) {\n')
        new_lines.append('                getSignatureTimestampOrNull(videoId)\n')
        new_lines.append('            }\n')
        new_lines.append('            val poTokenDeferred = async(Dispatchers.IO) {\n')
        new_lines.append('                if (!manualGvsPoToken.isNullOrBlank() && !manualPlayerPoToken.isNullOrBlank()) {\n')
        new_lines.append('                    PoTokenResult(playerRequestPoToken = manualPlayerPoToken, streamingDataPoToken = manualGvsPoToken)\n')
        new_lines.append('                } else if (webClientPoTokenEnabled && MAIN_CLIENT.useWebPoTokens && sessionId != null) {\n')
        new_lines.append('                    Timber.tag(logTag).d("Generating PoToken for ${MAIN_CLIENT.clientName}")\n')
        new_lines.append('                    try {\n')
        new_lines.append('                        poTokenGenerator.getWebClientPoToken(videoId, sessionId)\n')
        new_lines.append('                    } catch (e: Exception) {\n')
        new_lines.append('                        Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")\n')
        new_lines.append('                        null\n')
        new_lines.append('                    }\n')
        new_lines.append('                } else null\n')
        new_lines.append('            }\n')
        new_lines.append('\n')
        new_lines.append('            val signatureTimestamp = sigTimestampDeferred.await()\n')
        new_lines.append('                ?: cachedPublicVideoId?.let { fallbackId ->\n')
        new_lines.append('                    getSignatureTimestampOrNull(fallbackId)\n')
        new_lines.append('                }\n')
        new_lines.append('\n')
        new_lines.append('            var currentVideoId = videoId\n')
        new_lines.append('            if (enableFallback && databaseDao != null) {\n')
        new_lines.append('                val cachedFallback = databaseDao.getSetVideoId(videoId)\n')
        new_lines.append('                if (cachedFallback != null && cachedFallback.setVideoId != null) {\n')
        new_lines.append('                    currentVideoId = cachedFallback.setVideoId!!\n')
        new_lines.append('                }\n')
        new_lines.append('            }\n')
        skip = False
        in_conflict = False
    elif 'if (clientIndex == -1 || clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {' in line:
        # Conflict 4: validation check
        new_lines.append('                if (clientIndex == streamClients.size - 1 || isPrivatelyOwned) {\n')
        new_lines.append('                    if (isPrivatelyOwned) {\n')
        new_lines.append('                        Timber.tag(logTag).d("Skipping validation for privately owned/uploaded track (client: ${client.clientName})")\n')
        new_lines.append('                    } else {\n')
        new_lines.append('                        Timber.tag(logTag).d("Using last fallback client without validation: ${client.clientName}")\n')
        new_lines.append('                    }\n')
        new_lines.append('                    break\n')
        new_lines.append('                }\n')
        skip = False
        in_conflict = False
    elif 'Timber.tag(logTag).d("Stream validated successfully")' in line:
        # Conflict 5: validation success log
        new_lines.append('                    Timber.tag(logTag).d("Stream validated successfully with client: ${client.clientName}")\n')
        new_lines.append('                    break\n')
        new_lines.append('                } else {\n')
        new_lines.append('                    Timber.tag(logTag).d("Stream validation failed for client: ${client.clientName}")\n')
        skip = False
        in_conflict = False
    elif 'return try {' in line:
        # Conflict 6: validateStatus implementation
        new_lines.append('        Timber.tag(logTag).d("Validating stream URL status")\n')
        new_lines.append('        return try {\n')
        new_lines.append('            val clientParam = url.substringAfter("?", "").split(\'&\')\n')
        new_lines.append('                .firstOrNull { it.startsWith("c=") }\n')
        new_lines.append('                ?.substringAfter(\'=\')\n')
        new_lines.append('\n')
        new_lines.append('            val vClient = httpClient.newBuilder()\n')
        new_lines.append('                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)\n')
        new_lines.append('                .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)\n')
        new_lines.append('                .build()\n')
        new_lines.append('\n')
        new_lines.append('            val requestBuilder = okhttp3.Request.Builder()\n')
        new_lines.append('                .head()\n')
        new_lines.append('                .url(url)\n')
        new_lines.append('                .header("User-Agent", StreamClientUtils.resolveUserAgent(clientParam))\n')
        new_lines.append('\n')
        new_lines.append('            val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)\n')
        new_lines.append('            originReferer.origin?.let { requestBuilder.addHeader("Origin", it) }\n')
        new_lines.append('            originReferer.referer?.let { requestBuilder.addHeader("Referer", it) }\n')
        new_lines.append('\n')
        new_lines.append('            YouTube.cookie?.let { requestBuilder.addHeader("Cookie", it) }\n')
        new_lines.append('            vClient.newCall(requestBuilder.build()).execute().use { it.isSuccessful }\n')
        skip = False
        in_conflict = False

with open(path, 'w') as f:
    f.writelines(new_lines)
