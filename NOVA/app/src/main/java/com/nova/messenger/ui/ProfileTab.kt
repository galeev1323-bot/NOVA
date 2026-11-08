package com.nova.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.nova.messenger.data.*
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun ProfileEffect(effect: String, modifier: Modifier) {
    if (effect == "none") return
    val t = rememberInfiniteTransition(label = "fx")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse), label = "p")
    Canvas(modifier) {
        if (effect == "pulse") {
            drawCircle(Brush.radialGradient(listOf(Violet.copy(alpha = 0.55f * (1f - p)), Color.Transparent), center, size.minDimension * (0.35f + 0.5f * p)),
                radius = size.minDimension * (0.35f + 0.5f * p))
        } else {
            for (i in 0 until 18) {
                val a = i * 0.349f; val r = size.minDimension * (0.25f + 0.2f * ((i % 5) / 5f))
                val tw = ((sin((p * 6.28f) + i) + 1f) / 2f)
                drawCircle(Color(if (i % 2 == 0) 0xFF22D3EE else 0xFFFFC857).copy(alpha = tw), 3.dp.toPx() * (0.5f + tw),
                    Offset(center.x + cos(a) * r * 1.6f, center.y + sin(a) * r))
            }
        }
    }
}

@Composable
fun ProfileTab(nav: NavHostController) {
    val me by Graph.chats.me.collectAsState()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { scope.launch { runCatching { Graph.chats.uploadAvatar(it) }.onFailure { e -> snack.showSnackbar(e.message ?: "Ошибка") } } }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Header("Профиль")
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                ProfileEffect(if (me?.isPremium == true) me!!.profileEffect else "none", Modifier.fillMaxWidth().height(220.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box { ProfileAvatar(me, 104.dp)
                        Box(Modifier.align(Alignment.BottomEnd).size(34.dp).clip(RoundedCornerShape(17.dp)).background(NovaGradient).clickable { pickAvatar.launch("image/*") }, Alignment.Center) {
                            Icon(Icons.Rounded.PhotoCamera, "Сменить аватар", tint = Color.White, modifier = Modifier.size(18.dp)) } }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(me?.displayName ?: "…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        if (me?.isPremium == true) { Spacer(Modifier.width(6.dp)); PremiumBadge(20.dp) }
                    }
                    Text("@${me?.username ?: ""}", color = Cyan)
                    if (!me?.bio.isNullOrBlank()) Text(me!!.bio!!, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingRow(Icons.Rounded.Edit, "Редактировать профиль") { nav.navigate("settings/edit") }
                SettingRow(Icons.Rounded.Notifications, "Уведомления") { nav.navigate("settings/notifications") }
                SettingRow(Icons.Rounded.Visibility, "Конфиденциальность") { nav.navigate("settings/privacy") }
                SettingRow(Icons.Rounded.Block, "Чёрный список") { nav.navigate("settings/blocked") }
                SettingRow(Icons.Rounded.DarkMode, "Тема приложения") { nav.navigate("settings/theme") }
                SettingRow(Icons.Rounded.Security, "Безопасность") { nav.navigate("settings/security") }
                SettingRow(Icons.Rounded.WorkspacePremium, if (me?.isPremium == true) "Управление Premium" else "NOVA Premium") { nav.navigate("settings/premium") }
                SettingRow(Icons.AutoMirrored.Rounded.Logout, "Выйти из аккаунта", danger = true) { scope.launch { runCatching { Graph.auth.signOut() } } }
            }
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).pressScale(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else Cyan)
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (!danger) Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsScreen(page: String, nav: NavHostController) {
    val me by Graph.chats.me.collectAsState()
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val premium = me?.isPremium == true
    val title = mapOf("edit" to "Редактирование профиля", "notifications" to "Уведомления", "privacy" to "Конфиденциальность",
        "blocked" to "Чёрный список", "theme" to "Тема", "security" to "Безопасность", "premium" to "Управление Premium")[page] ?: ""
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header(title, onBack = { nav.popBackStack() })
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "edit" -> {
                    var dn by remember(me?.id) { mutableStateOf(me?.displayName ?: "") }
                    var un by remember(me?.id) { mutableStateOf(me?.username ?: "") }
                    var bio by remember(me?.id) { mutableStateOf(me?.bio ?: "") }
                    NovaField(dn, { dn = it }, "Имя", leading = Icons.Rounded.Person)
                    NovaField(un, { un = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, "@username", leading = Icons.Rounded.AlternateEmail)
                    NovaField(bio, { bio = it.take(140) }, "О себе", singleLine = false)
                    NovaButton("Сохранить") {
                        scope.launch {
                            runCatching {
                                if (!Regex("^[a-z0-9_]{3,32}$").matches(un)) error("@username: 3–32 символа, a–z, 0–9, _")
                                if (un != me?.username && !Graph.auth.usernameAvailable(un)) error("Этот @username уже занят")
                                Graph.chats.updateProfile { put("display_name", dn.trim()); put("username", un); put("bio", bio.trim()) }
                                msg = "Сохранено"
                            }.onFailure { msg = it.message }
                        }
                    }
                    Text("Оформление (Premium)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Chooser("Рамка аватара", listOf("none" to "Нет", "neon" to "Neon", "gold" to "Gold", "aurora" to "Aurora"), me?.avatarFrame ?: "none", premium) { v -> scope.launch { runCatching { Graph.chats.updateProfile { put("avatar_frame", v) } }.onFailure { msg = it.message } } }
                    Chooser("Тема чатов", listOf("default" to "Обычная", "aurora" to "Aurora", "sunset" to "Sunset", "matrix" to "Matrix"), me?.chatTheme ?: "default", premium) { v -> scope.launch { runCatching { Graph.chats.updateProfile { put("chat_theme", v) } }.onFailure { msg = it.message } } }
                    Chooser("Эффект профиля", listOf("none" to "Нет", "pulse" to "Pulse", "sparkles" to "Sparkles"), me?.profileEffect ?: "none", premium) { v -> scope.launch { runCatching { Graph.chats.updateProfile { put("profile_effect", v) } }.onFailure { msg = it.message } } }
                    if (!premium) Text("🔒 Эти опции открываются с NOVA Premium", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                "notifications" -> {
                    ToggleRow("Уведомления о сообщениях", Graph.settings.notifications) { Graph.settings.updateNotifications(it) }
                    ToggleRow("Показывать текст сообщения", Graph.settings.preview) { Graph.settings.updatePreview(it) }
                    Text("Уведомления приходят, пока приложение запущено. Для push при закрытом приложении нужен FCM (см. README).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "privacy" -> {
                    ToggleRow("Скрывать время последнего визита", me?.hideLastSeen == true) { v -> scope.launch { runCatching { Graph.chats.updateProfile { put("hide_last_seen", v) } } } }
                    Text("Когда включено, другие видят только «был(а) недавно» и не видят статус «в сети».", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "blocked" -> {
                    var list by remember { mutableStateOf<List<Profile>?>(null) }
                    LaunchedEffect(Unit) { list = runCatching { Graph.chats.blocked() }.getOrDefault(emptyList()) }
                    when {
                        list == null -> CircularProgressIndicator()
                        list!!.isEmpty() -> EmptyState("Список пуст", "Заблокированные пользователи появятся здесь")
                        else -> list!!.forEach { u ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                ProfileAvatar(u, 44.dp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) { Text(u.displayName, fontWeight = FontWeight.SemiBold); Text("@${u.username}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                TextButton({ scope.launch { runCatching { Graph.chats.unblock(u.id) }; list = list?.filter { it.id != u.id } } }) { Text("Разблокировать") }
                            }
                        }
                    }
                }
                "theme" -> listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе").forEach { (k, l) ->
                    Row(Modifier.fillMaxWidth().clickable { Graph.settings.updateTheme(k) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(Graph.settings.theme == k, { Graph.settings.updateTheme(k) }); Text(l)
                    }
                }
                "security" -> {
                    var pw by remember { mutableStateOf("") }
                    NovaField(pw, { pw = it }, "Новый пароль (мин. 8 символов)", password = true, leading = Icons.Rounded.Lock)
                    NovaButton("Сменить пароль") { scope.launch { runCatching { if (pw.length < 8) error("Минимум 8 символов"); Graph.auth.changePassword(pw); pw = ""; msg = "Пароль изменён" }.onFailure { msg = it.message } } }
                    Text("Что важно знать", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                    Text("• Соединение с сервером защищено HTTPS/TLS.\n• Токены хранятся в зашифрованном хранилище Android.\n• Пароли не хранятся в приложении; хэширует их Supabase Auth.\n• Сквозное (E2E) шифрование НЕ реализовано: сообщения хранятся на сервере в обычном виде и доступны владельцу проекта.", fontSize = 14.sp)
                }
                "premium" -> {
                    Text(if (premium) "Premium активен" + (me?.premiumUntil?.let { " до ${it.take(10)}" } ?: "") else "Premium не активен")
                    Text("Покупка и отмена подписки выполняются в Google Play. Демо-режим включается на вкладке Premium.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            msg?.let { Text(it, color = Cyan) }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked, onChange)
    }

@Composable
private fun Chooser(label: String, options: List<Pair<String, String>>, current: String, enabled: Boolean, onPick: (String) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (k, l) -> FilterChip(current == k, { onPick(k) }, { Text(l) }, enabled = enabled || k == current || k == "none" || k == "default") }
        }
    }
}
