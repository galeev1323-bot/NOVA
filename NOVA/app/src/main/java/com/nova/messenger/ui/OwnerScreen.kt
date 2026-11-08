package com.nova.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nova.messenger.data.Graph
import com.nova.messenger.data.Profile
import kotlinx.coroutines.launch

@Composable
fun OwnerScreen(nav: NavHostController) {
    val allowed by Graph.chats.isOwnerOrAdmin.collectAsState()
    val balance by Graph.chats.starsBalance.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10") }
    var reason by remember { mutableStateOf("Начисление владельцем") }
    var results by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var selected by remember { mutableStateOf<Profile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var premiumDays by remember { mutableIntStateOf(30) }
    var showPremiumDurations by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { Graph.chats.refreshStaffAccess(); Graph.chats.refreshStars() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Header("Меню владельца", onBack = { nav.popBackStack() })
        if (!allowed) {
            Text("Доступ запрещён. Проверь права владельца в базе данных.", modifier = Modifier.padding(20.dp))
            return@Column
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Мой кошелёк", style = MaterialTheme.typography.titleMedium)
                        Text("$balance звёзд", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            Text("Начислить звёзды пользователю", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { value ->
                    query = value
                    selected = null
                    message = null
                    if (value.trim().length >= 2) scope.launch {
                        results = runCatching { Graph.chats.searchUsers(value) }.getOrDefault(emptyList())
                    } else results = emptyList()
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по имени или @username") },
                singleLine = true,
            )
            selected?.let {
                Text("Выбран: ${it.displayName} (@${it.username})", color = MaterialTheme.colorScheme.primary)
            }
            results.take(8).forEach { user ->
                ListItem(
                    headlineContent = { Text(user.displayName.ifBlank { user.username }) },
                    supportingContent = { Text("@${user.username}") },
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = user
                        query = "@${user.username}"
                        results = emptyList()
                    },
                )
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it.filter(Char::isDigit).take(7) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Количество звёзд") },
                singleLine = true,
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(160) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Причина начисления") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val target = selected
                    val count = amount.toLongOrNull()
                    if (target == null) { message = "Сначала выбери пользователя"; return@Button }
                    if (count == null || count !in 1L..1_000_000L) { message = "Введи число от 1 до 1 000 000"; return@Button }
                    busy = true
                    scope.launch {
                        runCatching { Graph.chats.grantStars(target.id, count, reason) }
                            .onSuccess { message = "Начислено $count звёзд пользователю @${target.username}" }
                            .onFailure { message = it.message ?: "Не удалось начислить звёзды" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Начисляем…" else "Начислить звёзды") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text(
                "Все начисления проверяются сервером и записываются в журнал операций.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Выдать NOVA Premium", style = MaterialTheme.typography.titleMedium)
            Text(
                "Выбери пользователя выше и срок действия Premium. Выдача подтверждается сервером и доступна только владельцу проекта.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                OutlinedButton(
                    onClick = { showPremiumDurations = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    Text(if (premiumDays == 0) "Навсегда" else "$premiumDays дней")
                }
                DropdownMenu(
                    expanded = showPremiumDurations,
                    onDismissRequest = { showPremiumDurations = false },
                ) {
                    listOf(7, 30, 90, 180, 365, 0).forEach { days ->
                        DropdownMenuItem(
                            text = { Text(if (days == 0) "Навсегда" else "$days дней") },
                            onClick = {
                                premiumDays = days
                                showPremiumDurations = false
                            },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    val target = selected
                    if (target == null) {
                        message = "Сначала найди и выбери пользователя"
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        runCatching { Graph.chats.grantPremium(target.id, premiumDays) }
                            .onSuccess {
                                message = "Premium выдан пользователю @${target.username}: " +
                                    if (premiumDays == 0) "навсегда" else "на $premiumDays дней"
                            }
                            .onFailure { message = it.message ?: "Не удалось выдать Premium" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB8860B)),
            ) {
                Text(if (busy) "Выполняем…" else "Выдать Premium")
            }
        }
    }
}
