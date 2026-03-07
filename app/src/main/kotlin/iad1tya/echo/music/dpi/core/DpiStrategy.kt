package iad1tya.echo.music.dpi.core

enum class DpiStrategy(val title: String, val params: String) {
    DEFAULT("Стандарт (Без обхода)", ""),
    SPLIT_1("Split (Часть 1)", "-s1"),
    SPLIT_2("Split (Часть 2)", "-s2"),
    DISORDER_1("Disorder (Часть 1)", "-d1"),
    FAKE_SPLIT("Fake + Split", "-f -s1")
}
