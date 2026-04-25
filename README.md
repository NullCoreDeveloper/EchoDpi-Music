<div align="center">
  <img src="assets/Echo_github.png" alt="EchoDpi Music Logo" width="140"/>

  <h1>EchoDpi Music</h1>

  <p><strong>Форк проекта <a href="https://github.com/iad1tya/Echo-Music">Echo Music</a> с интегрированным механизмом обхода DPI (замедления и блокировок YouTube Music).</strong></p>
</div>

---

## 🎵 О проекте

**EchoDpi Music** — это модификация популярного open-source плеера Echo Music, созданная специально для бесперебойного доступа к музыке в условиях блокировок и глубокого анализа трафика (DPI/ТСПУ).

Мы реализовали обход на сетевом уровне (через разделение пакетов в сокетах и кастомный OkHttp Interceptor), поэтому **плеер работает без использования VpnService**, не конфликтуя с вашими VPN-приложениями и AdGuard.

### ✨ Главные отличия от оригинала (Echo Music):
- **Встроенный обход DPI**: Защита от замедления и блокировок "из коробки" за счет фрагментации HTTP/TLS пакетов.
- **DPI Config**: Выбор различных стратегий обхода (Chunking, Fake SNI) и автоматический Prober для поиска лучшей.
- **Отсутствие трекинга**: Полностью удалены Firebase Analytics и Crashlytics, нет слежки со стороны Google.
- **Независимая установка**: Устанавливается рядом с оригинальным клиентом как отдельное приложение (`iad1tya.echo.music.dpi`), не конфликтуя с ним.

---

## 🆕 Что нового (Echo Music v4.2.2 + DPI):

### Основные обновления оригинала:
- **Completely redesigned UI** — Cleaner and faster experience from the ground up.
- **Import from Spotify** — Bring your playlists and tracks over with ease.
- **Listen Together** - allows users to sync music in real time, similar to Spotify Jam.
- **Podcast support** — Listen to podcasts alongside your music library.
- **Local media support** — Play music stored directly on your device.
- **Auto data migration** — Seamlessly move existing app data to the new version.
- **Android Dynamic Island support** — Enhanced playback notifications on supported devices.

### Потоковая передача и воспроизведение:
- **Ad-Free** — Stream without interruptions.
- **Seamless Playback** — Switch effortlessly between audio-only and video modes.
- **Background Playback** — Listen while using other apps or with the screen off.
- **Offline Mode** — Download tracks, albums, and playlists via a dedicated download manager.
- **Crossfade** — Smooth transitions between tracks.
- **Canvas Animations** — Visual animations while playing music.

### Lyrics & Discovery:
- **Echo Find** — Identify songs playing around you using advanced audio recognition.
- **Smart Recommendations** — Personalized suggestions based on your listening history.
- **Multiple lyrics animations** — Choose from various lyric display styles.
- **AI lyrics translation** — Built-in Google Translate integration.

---

## 🚀 Установка

Скачайте скомпилированный APK файл из вкладки [Releases](../../releases/latest).

---

## 🛠 Сборка из исходников

```bash
git clone https://github.com/NullCoreDeveloper/EchoDpi-Music.git
cd EchoDpi-Music
./gradlew assembleUniversalRelease
```
*Firebase больше не требуется, проект собирается в одно действие.*

---

## Community & Support

Join the community for updates, discussions, and help.

<div align="center">
  <a href="https://discord.gg/EcfV3AxH5c"><img src="assets/discord.png" width="140"/></a>
  &nbsp;
  <a href="https://t.me/EchoMusicApp"><img src="assets/telegram.png" width="130"/></a>
</div>

---

## ⚖️ Лицензия

Проект распространяется под той же лицензией, что и кодовая база оригинала — <a href="LICENSE">GPL-3.0</a>. Отдельная благодарность разработчику оригинального [Echo Music](https://github.com/iad1tya/Echo-Music).

<div align="center">
  Licensed under <a href="LICENSE">GPL-3.0</a>
</div>
