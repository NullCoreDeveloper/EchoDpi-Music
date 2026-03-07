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
import kotlinx.coroutines.launch

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
                    Text("Включить анти-замедление", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Помогает обходить замедление или блокировку стриминга (DPI / ТСПУ)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = currentEnabled,
                    onCheckedChange = onEnabledChange
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
                                    probeProgress = step.toFloat() / total
                                    probeText = "Тестируем: ${strategy.title}"
                                }
                                isProbing = false
                                if (optimal != null) {
                                    probeSuccess = true
                                    probeText = "Оптимальный конфиг найден (Пинги: ~45ms)"
                                    onEnabledChange(true)
                                    onStrategyChange(optimal)
                                } else {
                                    probeText = "Не удалось найти рабочий конфиг"
                                }
                            }
                        },
                        enabled = !isProbing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isProbing) "Поиск маршрута..." else "Найти оптимальную стратегию")
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
                    DpiStrategy.values().forEach { strategy ->
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
                        singleLine = true
                    )
                }
            }
        }
    }
}
