package com.nova.messenger.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.nova.messenger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatsViewModel : ViewModel() {
    val chats = Graph.db.dao().chats().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var query by mutableStateOf(""); var searching by mutableStateOf(false)
    var results by mutableStateOf<List<Message>>(emptyList())
    private var job: Job? = null
    init { refresh() }
    fun refresh() { viewModelScope.launch { runCatching { Graph.chats.refreshChats() } } }
    fun onQuery(q: String) {
        query = q; job?.cancel()
        if (q.length < 2) { results = emptyList(); return }
        job = viewModelScope.launch { delay(300); results = runCatching { Graph.chats.searchMessages(q) }.getOrDefault(emptyList()) }
    }
}

@Composable
fun ChatsTab(nav: NavHostController) {
    val vm: ChatsViewModel = viewModel()
    val chats by vm.chats.collectAsState()
    val shown = chats.filter { vm.query.isBlank() || (it.title ?: "").contains(vm.query, true) }
    Column(Modifier.fillMaxSize()) {
        Header("NOVA", actions = {
            IconButton({ vm.searching = !vm.searching; if (!vm.searching) vm.onQuery("") }) {
                Icon(if (vm.searching) Icons.Rounded.Close else Icons.Rounded.Search, "Поиск")
            }
        })
        AnimatedVisibility(vm.searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            NovaField(vm.query, vm::onQuery, "Поиск по чатам и сообщениям", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), leading = Icons.Rounded.Search)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(shown, key = { it.id }) { c -> ChatRow(c, Modifier.animateItem()) { nav.navigate("chat/${c.id}") } }
            if (vm.results.isNotEmpty()) {
                item { Text("Сообщения", Modifier.padding(16.dp, 12.dp), color = Cyan, fontWeight = FontWeight.SemiBold) }
                items(vm.results, key = { "m" + it.id }) { m ->
                    val t = chats.firstOrNull { it.id == m.chatId }?.title ?: "Чат"
                    Column(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${m.chatId}") }.padding(16.dp, 8.dp)) {
                        Text(t, fontWeight = FontWeight.SemiBold)
                        Text(m.body ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (shown.isEmpty() && vm.results.isEmpty()) item {
                EmptyState("Пока нет чатов", "Найдите людей во вкладке «Контакты» и напишите первое сообщение")
            }
        }
    }
}

@Composable
private fun ChatRow(c: Chat, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp).clip(RoundedCornerShape(18.dp))
            .pressScale(onClick = onClick).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.avatarUrl, c.title ?: "?", 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title ?: "Чат", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.lastBody ?: "Нет сообщений", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtTime(c.lastAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.unread > 0) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.defaultMinSize(22.dp, 22.dp).clip(CircleShape).background(NovaGradient).padding(horizontal = 6.dp), Alignment.Center) {
                    Text(if (c.unread > 99) "99+" else c.unread.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ======================= КОНТАКТЫ =======================
class ContactsViewModel : ViewModel() {
    var query by mutableStateOf(""); var users by mutableStateOf<List<Profile>>(emptyList())
    var error by mutableStateOf<String?>(null)
    private var job: Job? = null
    fun onQuery(q: String) {
        query = q; job?.cancel()
        if (q.trim().length < 2) { users = emptyList(); return }
        job = viewModelScope.launch { delay(300); users = runCatching { Graph.chats.searchUsers(q) }.getOrDefault(emptyList()) }
    }
    fun open(userId: String, go: (String) -> Unit) = viewModelScope.launch {
        runCatching { Graph.chats.openDm(userId) }.onSuccess(go).onFailure { error = it.message }
    }
    fun createGroup(title: String, ids: List<String>, go: (String) -> Unit) = viewModelScope.launch {
        runCatching { Graph.chats.createGroup(title, ids) }.onSuccess(go).onFailure { error = it.message }
    }
}

@Composable
fun ContactsTab(nav: NavHostController) {
    val vm: ContactsViewModel = viewModel()
    val chats by Graph.db.dao().chats().collectAsState(emptyList())
    var groupDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("Контакты", actions = { IconButton({ groupDialog = true }) { Icon(Icons.Rounded.GroupAdd, "Новая группа") } })
        NovaField(vm.query, vm::onQuery, "Поиск по имени или @username", Modifier.padding(horizontal = 16.dp), leading = Icons.Rounded.Search)
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (vm.query.trim().length >= 2) {
                items(vm.users, key = { it.id }) { u -> UserRow(u) { vm.open(u.id) { nav.navigate("chat/$it") } } }
                if (vm.users.isEmpty()) item { EmptyState("Никого не найдено", "Проверьте написание имени или @username") }
            } else {
                val dms = chats.filter { !it.isGroup }
                item { Text("Ваши собеседники", Modifier.padding(16.dp, 12.dp), color = Cyan, fontWeight = FontWeight.SemiBold) }
                items(dms, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${c.id}") }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.avatarUrl, c.title ?: "?", 44.dp); Spacer(Modifier.width(12.dp)); Text(c.title ?: "", fontWeight = FontWeight.Medium)
                    }
                }
                if (dms.isEmpty()) item { EmptyState("Здесь пока пусто", "Введите имя или @username, чтобы найти человека") }
            }
        }
    }
    if (groupDialog) GroupDialog(vm, { groupDialog = false }) { nav.navigate("chat/$it") }
}

@Composable
private fun UserRow(u: Profile, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().pressScale(onClick = onClick).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ProfileAvatar(u, 48.dp); Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(u.displayName, fontWeight = FontWeight.SemiBold)
                if (u.isPremium) { Spacer(Modifier.width(4.dp)); PremiumBadge() }
            }
            Text("@${u.username}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun GroupDialog(vm: ContactsViewModel, onClose: () -> Unit, go: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    val picked = remember { mutableStateMapOf<String, Profile>() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Новая группа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NovaField(title, { title = it }, "Название группы")
                NovaField(vm.query, vm::onQuery, "Найти участников")
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(vm.users, key = { it.id }) { u ->
                        Row(Modifier.fillMaxWidth().clickable { if (picked.remove(u.id) == null) picked[u.id] = u }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(picked.containsKey(u.id), { if (picked.remove(u.id) == null) picked[u.id] = u })
                            Text("${u.displayName} (@${u.username})", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (picked.isNotEmpty()) Text("Выбрано: ${picked.size}", color = Cyan)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && picked.isNotEmpty(), onClick = {
                vm.createGroup(title, picked.keys.toList(), go); onClose()
            }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}
