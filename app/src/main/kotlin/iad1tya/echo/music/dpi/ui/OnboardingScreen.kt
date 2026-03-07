package iad1tya.echo.music.dpi.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import iad1tya.echo.music.dpi.core.DpiAutoProber
import iad1tya.echo.music.dpi.core.DpiStrategy
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    onConfigured: (DpiStrategy?) -> Unit
) {
    var currentStep by remember { mutableStateOf(0) }
    var totalSteps by remember { mutableStateOf(4) }
    var currentStrategy by remember { mutableStateOf<DpiStrategy?>(null) }
    var isDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prober = DpiAutoProber()
        val optimal = prober.findOptimalStrategy { step, total, strategy ->
            currentStep = step
            totalSteps = total
            currentStrategy = strategy
        }
        isDone = true
        delay(1000) // Показываем зеленую галочку секунду перед переходом
        onConfigured(optimal)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AnimatedContent(
                targetState = isDone,
                transitionSpec = {
                    fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
                },
                label = "onboarding_animation"
            ) { done ->
                if (!done) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 6.dp
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Настройка безопасного соединения...",
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Пожалуйста, подождите пару секунд, мы подбираем оптимальный маршрут для вашего провайдера.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Проверка: ${currentStrategy?.title ?: "Инициализация..."}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        LinearProgressIndicator(
                            progress = currentStep.toFloat() / totalSteps.coerceAtLeast(1),
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .padding(top = 8.dp)
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.checkbox_on_background),
                            contentDescription = "Успех",
                            tint = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Соединение установлено!",
                            style = MaterialTheme.typography.headlineSmall,
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }
    }
}
