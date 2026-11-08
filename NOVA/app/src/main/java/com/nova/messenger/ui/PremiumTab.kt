package com.nova.messenger.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.messenger.data.*
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.launch

private val perks = listOf(
    Triple(Icons.Rounded.Palette, "Анимированные темы", "Живые градиентные фоны чатов"),
    Triple(Icons.Rounded.Face, "Рамки аватара", "Neon, Gold и Aurora вокруг аватара"),
    Triple(Icons.Rounded.Verified, "Значок Premium", "Золотая галочка рядом с именем"),
    Triple(Icons.Rounded.EmojiEmotions, "Больше реакций", "6 дополнительных реакций"),
    Triple(Icons.Rounded.Pets, "Эксклюзивные стикеры", "Крупные стикеры в чатах"),
    Triple(Icons.Rounded.CloudUpload, "Файлы до 50 МБ", "Вместо 10 МБ в бесплатной версии"),
    Triple(Icons.Rounded.Gif, "Анимированные аватары", "Загружайте GIF-аватар"),
    Triple(Icons.Rounded.Tune, "Настройки оформления", "Тема чата и стиль профиля"),
    Triple(Icons.Rounded.AutoAwesome, "Эффекты профиля", "Pulse и Sparkles в шапке профиля"),
)

@Composable
fun PremiumTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me by Graph.chats.me.collectAsState()
    val snack = remember { SnackbarHostState() }
    val billing = remember { BillingManager(ctx) { msg -> scope.launch { snack.showSnackbar(msg) } } }
    var busy by remember { mutableStateOf(false) }
    val premium = me?.isPremium == true
    val inf = rememberInfiniteTransition(label = "prem")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "rot")
    val pulse by inf.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "pulse")
    val shift by inf.animateFloat(0f, 900f, infiniteRepeatable(tween(5000, easing = LinearEasing), RepeatMode.Reverse), label = "shift")

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF5B21B6), Color(0xFF0E7490), Color(0xFF7C3AED)), Offset(shift, 0f), Offset(shift + 700f, 700f)))
                    .padding(28.dp), contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(64.dp).scale(pulse), tint = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Text("NOVA PREMIUM", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    Text(if (premium) "Premium активен" else "Больше возможностей для общения", color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                    if (premium) me?.premiumUntil?.let { Text("до ${fmtDate(it)}", color = Gold, fontWeight = FontWeight.SemiBold) }
                }
            }
            perks.forEachIndexed { i, (icon, title, sub) -> PerkCard(icon, title, sub, i) }

            if (!premium) {
                NovaButton("Оформить Premium через Google Play") { billing.buy(ctx as Activity) }
                Text("Оплата и продление — через Google Play. Подписка подтверждается на сервере.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/account/subscriptions"))) }, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Управление подпиской в Google Play")
                }
            }

            // ---- тестовый режим ----
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("🧪 ТЕСТОВЫЙ РЕЖИМ (демо)", color = Gold, fontWeight = FontWeight.Bold)
                Text("Включает Premium-функции на 7 дней для демонстрации. Это НЕ оплата и НЕ реальная подписка.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { Graph.client.postgrest.rpc(if (premium) "deactivate_demo_premium" else "activate_demo_premium"); Graph.chats.loadMe() }
                            .onFailure { snack.showSnackbar(it.message ?: "Ошибка") }
                        busy = false
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (premium) "Выключить демо-режим" else "Включить демо на 7 дней") }
            }
            Spacer(Modifier.height(16.dp))
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

private fun fmtDate(iso: String) = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
}.getOrDefault("")

@Composable
private fun PerkCard(icon: ImageVector, title: String, sub: String, index: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(index * 60L); a.animateTo(1f, tween(450)) }
    Row(
        Modifier.fillMaxWidth().alpha(a.value).offset(y = ((1f - a.value) * 24).dp).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(NovaGradient), Alignment.Center) { Icon(icon, null, tint = Color.White) }
        Spacer(Modifier.width(14.dp))
        Column { Text(title, fontWeight = FontWeight.SemiBold); Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
