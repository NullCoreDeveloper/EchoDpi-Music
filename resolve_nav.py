import sys

path = '/home/nethunter/Documents/EchoDpi-Music/app/src/main/kotlin/iad1tya/echo/music/ui/screens/NavigationBuilder.kt'
with open(path, 'r') as f:
    lines = f.readlines()

new_lines = []
skip = False
in_conflict = False

for line in lines:
    if '<<<<<<< HEAD' in line:
        in_conflict = True
        skip = True
        # We only have one conflict left here
        new_lines.append('    composable("settings/dpi") {\n')
        new_lines.append('        val context = androidx.compose.ui.platform.LocalContext.current\n')
        new_lines.append('        val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()\n')
        new_lines.append('        val currentEnabled by iad1tya.echo.music.utils.rememberPreference(iad1tya.echo.music.dpi.core.DpiConfig.DpiEnabledKey, defaultValue = true)\n')
        new_lines.append('        val currentStrategyName by iad1tya.echo.music.utils.rememberPreference(iad1tya.echo.music.dpi.core.DpiConfig.DpiStrategyKey, defaultValue = iad1tya.echo.music.dpi.core.DpiStrategy.DEFAULT.name)\n')
        new_lines.append('        val currentStrategy = iad1tya.echo.music.dpi.core.DpiStrategy.valueOf(currentStrategyName)\n')
        new_lines.append('        val currentParams by iad1tya.echo.music.utils.rememberPreference(iad1tya.echo.music.dpi.core.DpiConfig.DpiCustomParamsKey, defaultValue = "")\n')
        new_lines.append('\n')
        new_lines.append('        iad1tya.echo.music.dpi.ui.DpiSettingsScreen(\n')
        new_lines.append('            currentEnabled = currentEnabled,\n')
        new_lines.append('            onEnabledChange = { enabled ->\n')
        new_lines.append('                coroutineScope.launch(Dispatchers.IO) {\n')
        new_lines.append('                    context.dataStore.edit {\n')
        new_lines.append('                        it[iad1tya.echo.music.dpi.core.DpiConfig.DpiEnabledKey] = enabled\n')
        new_lines.append('                    }\n')
        new_lines.append('                }\n')
        new_lines.append('            },\n')
        new_lines.append('            currentStrategy = currentStrategy,\n')
        new_lines.append('            onStrategyChange = { strategy ->\n')
        new_lines.append('                coroutineScope.launch(Dispatchers.IO) {\n')
        new_lines.append('                    context.dataStore.edit {\n')
        new_lines.append('                        it[iad1tya.echo.music.dpi.core.DpiConfig.DpiStrategyKey] = strategy.name\n')
        new_lines.append('                    }\n')
        new_lines.append('                }\n')
        new_lines.append('            },\n')
        new_lines.append('            currentParams = currentParams,\n')
        new_lines.append('            onParamsChange = { params ->\n')
        new_lines.append('                coroutineScope.launch(Dispatchers.IO) {\n')
        new_lines.append('                    context.dataStore.edit {\n')
        new_lines.append('                        it[iad1tya.echo.music.dpi.core.DpiConfig.DpiCustomParamsKey] = params\n')
        new_lines.append('                    }\n')
        new_lines.append('                }\n')
        new_lines.append('            },\n')
        new_lines.append('            onBack = { navController.navigateUp() }\n')
        new_lines.append('        )\n')
        new_lines.append('    }\n')
        new_lines.append('    composable("settings/diagnostics") {\n')
        new_lines.append('        DiagnosticsSettings(navController, scrollBehavior)\n')
        new_lines.append('    }\n')
        new_lines.append('    composable("settings/network_troubleshoot") {\n')
        new_lines.append('        NetworkTroubleshootSettings(navController, scrollBehavior)\n')
        new_lines.append('    }\n')
    elif '=======' in line and in_conflict:
        pass
    elif '>>>>>>> upstream/main' in line and in_conflict:
        skip = False
        in_conflict = False
    elif not skip:
        new_lines.append(line)

with open(path, 'w') as f:
    f.writelines(new_lines)
