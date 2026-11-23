package com.nova.messenger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.nova.messenger.data.GiftCatalogItem
import com.nova.messenger.data.GiftRecord
import com.nova.messenger.data.Graph
import kotlinx.coroutines.launch

@Composable
fun StarsScreen(nav: NavHostController) {
    val balance by Graph.chats.starsBalance.collectAsState()
    val balanceError by Graph.chats.starsError.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10") }
    var note by remember { mutableStateOf("") }
    var gifts by remember { mutableStateOf<List<GiftCatalogItem>>(emptyList()) }
    var history by remember { mutableStateOf<List<GiftRecord>>(emptyList()) }
    var selectedGift by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { Graph.chats.refreshStars() }
        runCatching { gifts = Graph.chats.giftCatalog() }
            .onFailure { feedback = it.message ?: "Не удалось загрузить подарки" }
        runCatching { history = Graph.chats.myGifts() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Stars и подарки", onBack = { nav.popBackStack() })
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text("Мой баланс", style = MaterialTheme.typography.titleMedium)
                    Text("$balance ⭐", style = MaterialTheme.typography.headlineSmall)
                    balanceError?.let { Text("Не удалось обновить баланс: $it", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        Text("Перевести Stars", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.removePrefix("@").take(32); feedback = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("@username получателя") },
            singleLine = true,
        )
        OutlinedTextField(
            value = amount,
            onValueChange = { amount = it.filter(Char::isDigit).take(6); feedback = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Количество Stars") },
            singleLine = true,
        )
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(160) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Сообщение (необязательно)") },
            singleLine = true,
        )
        Button(
            onClick = {
                val count = amount.toLongOrNull()
                if (username.length !in 3..32) { feedback = "Введи корректный @username"; return@Button }
                if (count == null || count !in 1L..100_000L) { feedback = "Количество: от 1 до 100 000"; return@Button }
                busy = true
                scope.launch {
                    runCatching { Graph.chats.transferStars(username, count, note) }
                        .onSuccess { feedback = "Перевод выполнен. Остаток: $it ⭐" }
                        .onFailure { feedback = it.message ?: "Перевод не выполнен" }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Переводим…" else "Перевести Stars") }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("Отправить подарок", style = MaterialTheme.typography.titleLarge)
        Text("Подарок оплачивается из твоего баланса Stars.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.CardGiftcard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Выбери подарок ниже")
        }
        gifts.forEach { gift ->
            GiftChoice(gift = gift, selected = selectedGift == gift.id) {
                selectedGift = gift.id
                feedback = null
            }
        }
        Button(
            onClick = {
                val giftId = selectedGift
                if (username.length !in 3..32) { feedback = "Введи @username получателя"; return@Button }
                if (giftId == null) { feedback = "Сначала выбери подарок"; return@Button }
                busy = true
                scope.launch {
                    runCatching { Graph.chats.sendGift(username, giftId, note) }
                        .onSuccess { feedback = "Подарок отправлен пользователю @$username"; selectedGift = null; runCatching { history = Graph.chats.myGifts() } }
                        .onFailure { feedback = it.message ?: "Не удалось отправить подарок" }
                    busy = false
                }
            },
            enabled = !busy && gifts.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Отправляем…" else "Отправить подарок") }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("История подарков", style = MaterialTheme.typography.titleLarge)
        if (history.isEmpty()) {
            Text("Подарков пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            history.forEach { item ->
                val catalogItem = gifts.firstOrNull { it.id == item.giftId }
                val direction = if (item.senderId == Graph.chats.myId) "Ты отправил подарок" else "Тебе отправили подарок"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text((catalogItem?.emoji ?: "🎁") + "  " + direction, style = MaterialTheme.typography.titleSmall)
                        Text(catalogItem?.title ?: item.giftId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (item.message.isNotBlank()) Text(item.message)
                        Text(item.createdAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        feedback?.let { Text(it, color = if (it.contains("не ") || it.contains("Ошибка")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GiftChoice(gift: GiftCatalogItem, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${gift.emoji}  ${gift.title}", style = MaterialTheme.typography.titleMedium)
            Text("${gift.priceStars} ⭐", color = MaterialTheme.colorScheme.primary)
        }
    }
}
