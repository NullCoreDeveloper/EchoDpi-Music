import sys

path = '/home/nethunter/Documents/EchoDpi-Music/app/src/main/kotlin/iad1tya/echo/music/playback/MusicService.kt'
with open(path, 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
in_conflict = False

for line in lines:
    if line.startswith('<<<<<<< HEAD'):
        in_conflict = True
        # Check which conflict it is
        # We only have one left
        new_lines.append('                                iad1tya.echo.music.dpi.core.DpiConfig.applyTo(\n')
        new_lines.append('                                    OkHttpClient.Builder()\n')
        new_lines.append('                                        .dns(CloudflareDnsResolver)\n')
        new_lines.append('                                        .proxy(YouTube.proxy)\n')
        new_lines.append('                                        .addInterceptor { chain ->\n')
        new_lines.append('                                            val request = chain.request()\n')
        new_lines.append('                                            val clientParam = request.url.queryParameter("c")\n')
        new_lines.append('                                            val ua = StreamClientUtils.resolveUserAgent(clientParam)\n')
        new_lines.append('                                            val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)\n')
        new_lines.append('                                            val builder = request.newBuilder().header("User-Agent", ua)\n')
        new_lines.append('                                            originReferer.origin?.let { builder.header("Origin", it) }\n')
        new_lines.append('                                            originReferer.referer?.let { builder.header("Referer", it) }\n')
        new_lines.append('                                            chain.proceed(builder.build())\n')
        new_lines.append('                                        }\n')
        new_lines.append('                                        .connectTimeout(5, TimeUnit.SECONDS)\n')
        new_lines.append('                                        .readTimeout(8, TimeUnit.SECONDS)\n')
        new_lines.append('                                        .callTimeout(10, TimeUnit.SECONDS)\n')
        new_lines.append('                                        .proxyAuthenticator { _, response ->\n')
        new_lines.append('                                            YouTube.proxyAuth?.let { auth ->\n')
        new_lines.append('                                                response.request.newBuilder()\n')
        new_lines.append('                                                    .header("Proxy-Authorization", auth)\n')
        new_lines.append('                                                    .build()\n')
        new_lines.append('                                            } ?: response.request\n')
        new_lines.append('                                        }\n')
        new_lines.append('                                        .apply { YouTube.customClientBuilder?.invoke(this) }\n')
        new_lines.append('                                ).build(),\n')
        skip = True
    elif line.startswith('======='):
        pass
    elif line.startswith('>>>>>>> upstream/main'):
        skip = False
        in_conflict = False
    elif not skip:
        new_lines.append(line)

with open(path, 'w') as f:
    f.writelines(new_lines)
