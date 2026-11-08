package com.nova.messenger.ui

import android.content.Intent
import android.net.Uri
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.nova.messenger.data.*
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class MsgUi(val m: Message, val reactions: List<Pair<String, Int>>, val mine: String?, val replyTo: Message?)

val FREE_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
val PREMIUM_REACTIONS = listOf("🚀", "💎", "🦄", "👑", "🌈", "✨")
val STICKERS = listOf("🦄", "🐉", "🪐", "🌌", "🦊", "🐙", "🍕", "🎉")

class ChatViewModel(val chatId: String) : ViewModel() {
    private val repo = Graph.chats
    private val dao = Graph.db.dao()
    val myId get() = repo.myId
    val chat = dao.chat(chatId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val items = combine(dao.messages(chatId), dao.reactions(chatId)) { ms, rs ->
        val byId = ms.associateBy { it.id }
        ms.map { m ->
            val r = rs.filter { it.messageId == m.id }
            MsgUi(m, r.groupingBy { it.emoji }.eachCount().toList(), r.firstOrNull { it.userId == myId }?.emoji, m.replyTo?.let { byId[it] })
        }.reversed()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var text by mutableStateOf("")
    var replyTo by mutableStateOf<Message?>(null)
    var editing by mutableStateOf<Message?>(null)
    var peer by mutableStateOf<Profile?>(null)
    var typing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var recording by mutableStateOf(false)
    var busy by mutableStateOf(false)
    val names = mutableStateMapOf<String, Profile>()

    private var recorder: VoiceRecorder? = null
    private var typingJob: Job? = null
    private var lastTypingSent = 0L
    private val typingCh: RealtimeChannel = Graph.client.channel("typing-$chatId")

    init {
        repo.openChatId = chatId
        io { repo.loadMessages(chatId); repo.markRead(chatId) }
        val flow = typingCh.broadcastFlow<JsonObject>("typing")
        viewModelScope.launch { runCatching { typingCh.subscribe() } }
        flow.onEach {
            if (it["u"]?.jsonPrimitive?.contentOrNull != myId) {
                typing = true; typingJob?.cancel()
                typingJob = viewModelScope.launch { delay(3000); typing = false }
            }
        }.launchIn(viewModelScope)
        viewModelScope.launch {
            val c = chat.filterNotNull().first()
            c.otherUserId?.let { oid -> while (true) { peer = runCatching { repo.profile(oid) }.getOrNull() ?: peer; delay(30_000) } }
        }
        viewModelScope.launch {
            items.collect { list ->
                val ids = list.map { it.m.senderId }.distinct().filter { it != myId && it !in names }
                if (ids.isNotEmpty()) runCatching { repo.profiles(ids) }.getOrNull()?.forEach { names[it.id] = it }
                // входящее сообщение в открытом чате — сразу помечаем прочитанным
                if (list.firstOrNull()?.m?.senderId != myId) runCatching { repo.markRead(chatId) }
            }
        }
    }

    override fun onCleared() {
        if (repo.openChatId == chatId) repo.openChatId = null
        Graph.appScope.launch { runCatching { Graph.client.realtime.removeChannel(typingCh) } }
    }

    private fun io(b: suspend () -> Unit) { viewModelScope.launch { runCatching { b() }.onFailure { error = it.message } } }

    fun onTextChange(v: String) {
        text = v
        val now = System.currentTimeMillis()
        if (v.isNotEmpty() && now - lastTypingSent > 2000) {
            lastTypingSent = now
            viewModelScope.launch { runCatching { typingCh.broadcast("typing", buildJsonObject { put("u", myId ?: "") }) } }
        }
    }

    fun send() {
        val t = text.trim(); if (t.isEmpty()) return
        val e = editing; val r = replyTo
        text = ""; editing = null; replyTo = null
        io { if (e != null) repo.edit(e.id, t) else repo.send(chatId, t, replyTo = r?.id) }
    }
    fun sendSticker(s: String) = io { repo.send(chatId, s, replyTo = replyTo?.id, mediaType = "sticker"); replyTo = null }
    fun attach(uri: Uri) = io {
        busy = true
        try { val u = repo.upload(uri); repo.send(chatId, null, replyTo?.id, u.url, u.type, u.name); replyTo = null } finally { busy = false }
    }
    fun toggleRecord(ctx: android.content.Context) {
        if (!recording) {
            runCatching { recorder = VoiceRecorder(ctx).also { it.start() }; recording = true }.onFailure { error = "Не удалось начать запись" }
        } else {
            recording = false
            val f = recorder?.stop() ?: return
            io {
                busy = true
                try { val u = repo.uploadFile(f, "audio/mp4"); repo.send(chatId, null, replyTo?.id, u.url, "voice", "Голосовое сообщение") ; replyTo = null }
                finally { busy = false; f.delete() }
            }
        }
    }
    fun react(m: Message, emoji: String, current: String?) = io { repo.toggleReaction(m.id, emoji, current) }
    fun delete(m: Message) = io { repo.delete(m.id) }
    fun forward(m: Message, toChat: String) = io { repo.send(toChat, m.body, mediaUrl = m.mediaUrl, mediaType = m.mediaType, mediaName = m.mediaName, forwarded = true) }
    fun block() = io { peer?.id?.let { repo.block(it) } }
}

@Composable
fun ChatBackground(theme: String, content: @Composable BoxScope.() -> Unit) {
    val colors = when (theme) {
        "aurora" -> listOf(Color(0xFF0B3D4A), Color(0xFF3B1E6E), Color(0xFF0B3D4A))
        "sunset" -> listOf(Color(0xFF4A1B3D), Color(0xFF7A2E2E), Color(0xFF4A1B3D))
        "matrix" -> listOf(Color(0xFF021A0E), Color(0xFF0B3D1F), Color(0xFF021A0E))
        else -> null
    }
    if (colors == null) { AppBackground(content = content); return }
    val t = rememberInfiniteTransition(label = "bg")
    val off by t.animateFloat(0f, 1600f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "off")
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(colors, Offset(off, 0f), Offset(off + 900f, 1400f))), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(chatId: String, nav: NavHostController) {
    val vm: ChatViewModel = viewModel(key = chatId, factory = viewModelFactory { initializer { ChatViewModel(chatId) } })
    val ctx = LocalContext.current
    val chat by vm.chat.collectAsState()
    val items by vm.items.collectAsState()
    val me by Graph.chats.me.collectAsState()
    val allChats by Graph.db.dao().chats().collectAsState(emptyList())
    var sheet by remember { mutableStateOf<MsgUi?>(null) }
    var forward by remember { mutableStateOf<Message?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var showStickers by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { it?.let(vm::attach) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleRecord(ctx) }
    LaunchedEffect(vm.error) { vm.error?.let { snack.showSnackbar(it); vm.error = null } }
    val premium = me?.isPremium == true

    ChatBackground(me?.chatTheme?.takeIf { premium } ?: "default") {
        Column(Modifier.fillMaxSize()) {
            // ---- шапка ----
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                if (vm.peer != null) ProfileAvatar(vm.peer, 40.dp) else Avatar(chat?.avatarUrl, chat?.title ?: "?", 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(chat?.title ?: "Чат", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (vm.peer?.isPremium == true) { Spacer(Modifier.width(4.dp)); PremiumBadge() }
                    }
                    val sub = when {
                        vm.typing -> "печатает..."
                        chat?.isGroup == true -> "группа"
                        else -> statusText(vm.peer)
                    }
                    Text(sub, fontSize = 12.sp, color = if (vm.typing || sub == "в сети") Cyan else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (chat?.isGroup == false) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Меню") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Заблокировать") }, { vm.block(); menu = false; nav.popBackStack() }, leadingIcon = { Icon(Icons.Rounded.Block, null) })
                        }
                    }
                }
            }
            // ---- сообщения ----
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(items, key = { it.m.id }) { ui ->
                    val mine = ui.m.senderId == vm.myId
                    Bubble(ui, mine, chat?.isGroup == true, vm.names[ui.m.senderId], Modifier.animateItem(), premium,
                        onLong = { sheet = ui }, onReact = { e -> vm.react(ui.m, e, ui.mine) })
                }
            }
            // ---- панель ответа/редактирования ----
            AnimatedVisibility(vm.replyTo != null || vm.editing != null) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (vm.editing != null) Icons.Rounded.Edit else Icons.AutoMirrored.Rounded.Reply, null, tint = Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text((vm.editing ?: vm.replyTo)?.body ?: "[вложение]", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ vm.replyTo = null; vm.editing = null; vm.text = "" }) { Icon(Icons.Rounded.Close, "Отмена") }
                }
            }
            AnimatedVisibility(showStickers) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    STICKERS.forEach { s -> Text(s, fontSize = 32.sp, modifier = Modifier.pressScale { vm.sendSticker(s); showStickers = false }) }
                }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cyan)
            // ---- ввод ----
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ showAttach = true }) { Icon(Icons.Rounded.AttachFile, "Прикрепить") }
                IconButton({
                    if (premium) showStickers = !showStickers
                    else uiScope.launch { snack.showSnackbar("Эксклюзивные стикеры — в NOVA Premium") }
                }) { Icon(if (premium) Icons.Rounded.EmojiEmotions else Icons.Rounded.Lock, "Стикеры") }
                Box(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (vm.text.isEmpty()) Text(if (vm.recording) "● Запись... нажмите ■ для отправки" else "Сообщение", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(vm.text, vm::onTextChange, Modifier.fillMaxWidth(), maxLines = 5,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(Cyan))
                }
                Spacer(Modifier.width(6.dp))
                val hasText = vm.text.isNotBlank()
                val s by animateFloatAsState(if (hasText) 1.1f else 1f, spring(0.4f, 500f), label = "sendScale")
                Box(Modifier.size(46.dp).scale(s).clip(CircleShape).background(NovaGradient).pressScale {
                    if (hasText) vm.send()
                    else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) vm.toggleRecord(ctx)
                    else mic.launch(Manifest.permission.RECORD_AUDIO)
                }, Alignment.Center) {
                    Icon(
                        when { hasText -> Icons.AutoMirrored.Rounded.Send; vm.recording -> Icons.Rounded.Stop; else -> Icons.Rounded.Mic },
                        "Отправить", tint = Color.White,
                    )
                }
            }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
    }

    if (showAttach) ModalBottomSheet({ showAttach = false }) {
        Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
            listOf(Triple("Фото", Icons.Rounded.Image, "image/*"), Triple("Видео", Icons.Rounded.Videocam, "video/*"), Triple("Файл", Icons.Rounded.AttachFile, "*/*")).forEach { (l, ic, mime) ->
                ListItem({ Text(l) }, Modifier.clickable { showAttach = false; pick.launch(mime) }, leadingContent = { Icon(ic, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            Text("Лимит файла: ${if (premium) 50 else 10} МБ" + if (!premium) " (Premium — 50 МБ)" else "", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }

    sheet?.let { ui ->
        val clip = LocalClipboardManager.current
        ModalBottomSheet({ sheet = null }) {
            Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (FREE_REACTIONS + if (premium) PREMIUM_REACTIONS else emptyList()).forEach { e ->
                        Text(e, fontSize = 28.sp, modifier = Modifier.pressScale { vm.react(ui.m, e, ui.mine); sheet = null })
                    }
                }
                if (!premium) Text("🔒 Ещё 6 реакций — в Premium", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                @Composable fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: () -> Unit) =
                    ListItem({ Text(label) }, Modifier.clickable { on(); sheet = null }, leadingContent = { Icon(icon, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                Action("Ответить", Icons.AutoMirrored.Rounded.Reply) { vm.replyTo = ui.m }
                if (!ui.m.body.isNullOrEmpty()) Action("Копировать", Icons.Rounded.ContentCopy) { clip.setText(AnnotatedString(ui.m.body!!)) }
                Action("Переслать", Icons.AutoMirrored.Rounded.Send) { forward = ui.m }
                if (ui.m.senderId == vm.myId) {
                    if (ui.m.mediaType == null && ui.m.body != null) Action("Редактировать", Icons.Rounded.Edit) { vm.editing = ui.m; vm.text = ui.m.body!! }
                    Action("Удалить", Icons.Rounded.Delete) { vm.delete(ui.m) }
                }
            }
        }
    }

    forward?.let { m ->
        AlertDialog({ forward = null }, title = { Text("Переслать в…") }, text = {
            LazyColumn { items(allChats, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().clickable { vm.forward(m, c.id); forward = null }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.avatarUrl, c.title ?: "?", 36.dp); Spacer(Modifier.width(10.dp)); Text(c.title ?: "Чат")
                }
            } }
        }, confirmButton = {}, dismissButton = { TextButton({ forward = null }) { Text("Отмена") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(ui: MsgUi, mine: Boolean, group: Boolean, sender: Profile?, modifier: Modifier, premium: Boolean, onLong: () -> Unit, onReact: (String) -> Unit) {
    val ctx = LocalContext.current
    val m = ui.m
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)
    Box(modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (m.mediaType == "sticker") {
                Text(m.body ?: "", fontSize = 64.sp, modifier = Modifier.combinedClickable(onClick = {}, onLongClick = onLong))
            } else Column(
                Modifier.widthIn(max = 300.dp).clip(shape)
                    .background(if (mine) Brush.linearGradient(listOf(Color(0xFF6D3FE0), Color(0xFF0E8CB0))) else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
                    .combinedClickable(onClick = {}, onLongClick = onLong).padding(10.dp, 8.dp),
            ) {
                val fg = if (mine) Color.White else MaterialTheme.colorScheme.onSurface
                if (group && !mine && sender != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sender.displayName, color = Cyan, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (sender.isPremium) { Spacer(Modifier.width(3.dp)); PremiumBadge(13.dp) }
                }
                if (m.forwarded) Text("↪ Пересланное сообщение", fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
                ui.replyTo?.let { r ->
                    Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Cyan))
                        Text(r.body ?: "[вложение]", Modifier.padding(start = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = fg.copy(alpha = 0.75f))
                    }
                }
                when (m.mediaType) {
                    "image" -> AsyncImage(m.mediaUrl, m.mediaName, Modifier.widthIn(max = 260.dp).heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)).clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl))) }, contentScale = ContentScale.Fit)
                    "voice" -> Row(Modifier.clickable { m.mediaUrl?.let(AudioPlayer::toggle) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (AudioPlayer.playing == m.mediaUrl) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = fg)
                        Text("Голосовое сообщение", color = fg)
                    }
                    "video", "file" -> Row(Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl))) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (m.mediaType == "video") Icons.Rounded.Videocam else Icons.Rounded.AttachFile, null, tint = fg)
                        Spacer(Modifier.width(6.dp)); Text(m.mediaName ?: "Файл", color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!m.body.isNullOrEmpty()) Text(m.body, color = fg, fontSize = 16.sp)
                Text((if (m.editedAt != null) "изм. " else "") + fmtTime(m.createdAt, true), fontSize = 11.sp, color = fg.copy(alpha = 0.65f), modifier = Modifier.align(Alignment.End))
            }
            if (ui.reactions.isNotEmpty()) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ui.reactions.forEach { (e, n) ->
                    Text("$e $n", fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (ui.mine == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                        .clickable { onReact(e) }.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}
