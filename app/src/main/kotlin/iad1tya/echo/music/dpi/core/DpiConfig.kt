package iad1tya.echo.music.dpi.core

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object DpiConfig {
    val IsDpiConfiguredKey = booleanPreferencesKey("is_dpi_configured")
    val DpiEnabledKey = booleanPreferencesKey("dpi_enabled")
    val DpiStrategyKey = stringPreferencesKey("dpi_strategy")
    val DpiCustomParamsKey = stringPreferencesKey("dpi_custom_params")
}
