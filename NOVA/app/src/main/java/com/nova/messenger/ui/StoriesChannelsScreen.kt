package com.nova.messenger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun StoriesChannelsScreen() {
    val prefs = remember { Graph.app.getSharedPreferences("media_drafts", android.content.Context.MODE_PRIVATE) }
    var selected by remember { mutableIntStateOf(0) }
    var channelTitle by remember { mutableStateOf("") }
    var channelAbout by remember { mutableStateOf("") }
    var storyCaption by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("Черновики сохраняются на устройстве. Публикация пока не подключена.") }

    val initialStories: List<String> = remember { runCatching {
        val a = JSONArray(prefs.getString("stories", "[]"))
        (0 until a.length()).map { index -> a.getString(index) }
    }.getOrElse { emptyList<String>() } }
    val initialChannels: List<Pair<String, String>> = remember { runCatching {
        val a = JSONArray(prefs.getString("channels", "[]"))
        (0 until a.length()).map { index ->
            val o = a.getJSONObject(index)
            Pair(o.getString("title"), o.optString("about"))
        }
    }.getOrElse { emptyList<Pair<String, String>>() } }
    var stories by remember { mutableStateOf(initialStories) }
    var channels by remember { mutableStateOf(initialChannels) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            stories = stories + ("Медиа: " + it.toString())
            info = "Медиа URI сохранён в черновике. Для публикации нужно подключить Storage."
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { target ->
            runCatching {
                val channelArray = JSONArray()
                channels.forEach { pair ->
                    channelArray.put(JSONObject().put("title", pair.first).put("about", pair.second))
                }
                val backup = JSONObject()
                    .put("format", "NOVA_MEDIA_DRAFTS")
                    .put("version", 1)
                    .put("stories", JSONArray(stories))
                    .put("channels", channelArray)
                Graph.app.contentResolver.openOutputStream(target)?.use {
                    it.write(backup.toString(2).toByteArray(Charsets.UTF_8))
                } ?: error("Не удалось открыть файл")
                info = "Резервная копия сохранена."
            }.onFailure { info = "Ошибка экспорта: ${it.message ?: "неизвестно"}" }
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { source ->
            runCatching {
                val content = Graph.app.contentResolver.openInputStream(source)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Не удалось открыть файл")
                val backup = JSONObject(content)
                if (backup.optString("format") != "NOVA_MEDIA_DRAFTS") error("Это не резервная копия NOVA")
                val importedStories: List<String> = backup.optJSONArray("stories")?.let { a ->
                    (0 until a.length()).map { index -> a.getString(index) }
                } ?: emptyList()
                val importedChannels: List<Pair<String, String>> = backup.optJSONArray("channels")?.let { a ->
                    (0 until a.length()).map { index ->
                        val o = a.getJSONObject(index)
                        Pair(o.getString("title"), o.optString("about"))
                    }
                } ?: emptyList()
                stories = importedStories
                channels = importedChannels
                info = "Черновики восстановлены."
            }.onFailure { info = "Ошибка импорта: ${it.message ?: "неизвестно"}" }
        }
    }

    LaunchedEffect(stories) { prefs.edit().putString("stories", JSONArray(stories).toString()).apply() }
    LaunchedEffect(channels) {
        val array = JSONArray()
        channels.forEach { pair -> array.put(JSONObject().put("title", pair.first).put("about", pair.second)) }
        prefs.edit().putString("channels", array.toString()).apply()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("Истории и каналы")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportPicker.launch("nova-media-backup.json") }) { Icon(Icons.Rounded.Save, null); Text("Экспорт") }
            OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/json")) }) { Icon(Icons.Rounded.FolderOpen, null); Text("Импорт") }
        }
        TabRow(selectedTabIndex = selected) {
            Tab(selected == 0, { selected = 0 }, text = { Text("Истории") })
            Tab(selected == 1, { selected = 1 }, text = { Text("Каналы") })
        }
        Spacer(Modifier.height(12.dp))
        if (selected == 0) {
            Text("Создать черновик истории", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(storyCaption, { storyCaption = it }, label = { Text("Текст истории") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (storyCaption.isBlank()) info = "Напиши текст."
                    else { stories = stories + storyCaption.trim(); storyCaption = ""; info = "Черновик сохранён." }
                }) { Icon(Icons.Rounded.Add, null); Text("Текст") }
                OutlinedButton(onClick = { mediaPicker.launch("image/*") }) { Icon(Icons.Rounded.Photo, null); Text("Фото/видео") }
            }
            if (stories.isNotEmpty()) {
                Text("Черновики (${stories.size})", style = MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(stories.reversed()) { item -> ListItem(leadingContent = { Icon(Icons.Rounded.AutoStories, null) }, headlineContent = { Text(item) }) }
                }
                TextButton(onClick = { stories = emptyList() }) { Text("Очистить черновики") }
            }
            Text("Файлы и тексты пока не публикуются.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Создать канал (черновик)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(channelTitle, { channelTitle = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(channelAbout, { channelAbout = it }, label = { Text("Описание") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = {
                if (channelTitle.isBlank()) info = "Введите название."
                else { channels = channels + Pair(channelTitle.trim(), channelAbout.trim()); info = "Черновик канала сохранён."; channelTitle = ""; channelAbout = "" }
            }) { Icon(Icons.Rounded.Add, null); Text("Сохранить") }
            Spacer(Modifier.height(8.dp))
            Text("Мои каналы-черновики", style = MaterialTheme.typography.titleMedium)
            if (channels.isEmpty()) Text("Пока нет черновиков.")
            else LazyColumn(Modifier.weight(1f, fill = false)) {
                items(channels.reversed()) { pair ->
                    ListItem(leadingContent = { Icon(Icons.Rounded.Campaign, null) }, headlineContent = { Text(pair.first) }, supportingContent = { Text(pair.second.ifBlank { "Без описания" }) })
                }
            }
            if (channels.isNotEmpty()) TextButton(onClick = { channels = emptyList() }) { Text("Очистить") }
        }
        Spacer(Modifier.height(8.dp))
        Text(info, color = MaterialTheme.colorScheme.secondary)
    }
}
