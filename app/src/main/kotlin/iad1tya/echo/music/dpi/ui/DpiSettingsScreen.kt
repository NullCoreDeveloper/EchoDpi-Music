package iad1tya.echo.music.dpi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import iad1tya.echo.music.dpi.core.DpiAutoProber
import iad1tya.echo.music.dpi.core.DpiStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import iad1tya.echo.music.R
import iad1tya.echo.music.constants.*
import iad1tya.echo.music.dpi.core.DpiConfig
import iad1tya.echo.music.utils.rememberPreference
import iad1tya.echo.music.utils.dataStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DpiSettingsScreen(
    currentEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    currentStrategy: DpiStrategy,
    onStrategyChange: (DpiStrategy) -> Unit,
    currentParams: String,
    onParamsChange: (String) -> Unit,
    onBack: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showAdvanced by remember { mutableStateOf(false) }
    var isProbing by remember { mutableStateOf(false) }
    var probeProgress by remember { mutableStateOf(0f) }
    var probeText by remember { mutableStateOf("") }
    var probeSuccess by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Обход блокировок (DPI)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_revert), 
                            contentDescription = "Назад"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Главный тумблер
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.enable_anti_dpi_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.enable_anti_dpi_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = currentEnabled,
                    onCheckedChange = onEnabledChange
                )
            }

            val autoDisableDpiOnVpn by rememberPreference(DpiConfig.AutoDisableDpiOnVpnKey, defaultValue = false)
            val context = LocalContext.current

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            iad1tya.echo.music.utils.dataStore(context).edit {
                                it[iad1tya.echo.music.dpi.core.DpiConfig.AutoDisableDpiOnVpnKey] = !autoDisableDpiOnVpn
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.vpn_bypass_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.vpn_bypass_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoDisableDpiOnVpn,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            iad1tya.echo.music.utils.dataStore(context).edit {
                                it[iad1tya.echo.music.dpi.core.DpiConfig.AutoDisableDpiOnVpnKey] = enabled
                            }
                        }
                    }
                )
            }

            Divider()

            // Авто-перебор
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Волшебная кнопка",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            isProbing = true
                            probeSuccess = false
                            coroutineScope.launch {
                                val prober = DpiAutoProber()
                                val optimal = prober.findOptimalStrategy { step, total, strategy ->
                                    if (isProbing) { // Only update if not canceled
                                        probeProgress = step.toFloat() / total
                                        probeText = "Тестируем: ${strategy.title}"
                                    }
                                }
                                if (isProbing) {
                                    isProbing = false
                                    if (optimal != null) {
                                        probeSuccess = true
                                        probeText = "Оптимальный конфиг найден"
                                        onEnabledChange(true)
                                        // Force UI update
                                        onStrategyChange(optimal)
                                    } else {
                                        probeText = "Не удалось найти рабочий конфиг"
                                    }
                                }
                            }
                        },
                        enabled = !isProbing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isProbing) "Поиск маршрута..." else "Найти оптимальную стратегию")
                    }

                    if (isProbing) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { isProbing = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Остановить")
                        }
                    }

                    AnimatedVisibility(visible = isProbing || probeText.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(top = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (isProbing) {
                                LinearProgressIndicator(progress = probeProgress, modifier = Modifier.fillMaxWidth())
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = probeText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (probeSuccess) androidx.compose.ui.graphics.Color(0xFF388E3C) 
                                        else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Продвинутый режим
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAdvanced) "Скрыть ручную настройку ▲" else "Для гиков / Ручная настройка ▼")
            }

            AnimatedVisibility(visible = showAdvanced) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Выбор пресета", style = MaterialTheme.typography.titleSmall)
                    DpiStrategy.entries.forEach { strategy ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = currentStrategy == strategy,
                                onClick = { onStrategyChange(strategy) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(strategy.title)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = currentParams,
                        onValueChange = onParamsChange,
                        label = { Text("RAW параметры (для экспертов)") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Например: -s1 -d1 -f") },
                    )
                }
            }

            // Добавляем отступ снизу, чтобы можно было доскроллить до самого низа
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}
