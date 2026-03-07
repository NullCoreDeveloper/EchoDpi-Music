package iad1tya.echo.music.dpi.core

enum class DpiStrategy(val title: String, val params: String) {
    DEFAULT("Стандарт (Без обхода)", ""),
    SPLIT_1("Split (2 части)", "-s1"),
    SPLIT_2("Split (3 части)", "-s2"),
    OOB_INJECT("OOB + Split", "-oob"),
    SNI_FRAG("Фрагментация SNI", "-sni")
}
