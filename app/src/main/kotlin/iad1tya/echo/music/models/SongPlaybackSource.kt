package iad1tya.echo.music.models

enum class SongPlaybackSource(val value: Int) {
    AUTO(0),
    YOUTUBE_MUSIC(1),
    YOUTUBE(2);

    companion object {
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: AUTO
    }
}
