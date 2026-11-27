package com.nova.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StoriesChannelsScreen() {
    var selected by remember { mutableIntStateOf(0) }
    var channelTitle by remember { mutableStateOf("") }
    var channelAbout by remember { mutableStateOf("") }
    var storyCaption by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("Истории и каналы — интерфейс подготовлен. Подключение Supabase добавим позже.") }
    val demoChannels = listOf("NOVA новости", "Игры и технологии", "Музыка")
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Истории и каналы", style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex=selected) {
            Tab(selected==0,{selected=0},text={Text("Истории")})
            Tab(selected==1,{selected=1},text={Text("Каналы")})
        }
        Spacer(Modifier.height(12.dp))
        if(selected==0) {
            Text("Истории",style=MaterialTheme.typography.titleMedium)
            Text("Фото и видео будут отображаться здесь и исчезать через 24 часа после подключения базы.")
            OutlinedTextField(storyCaption,{storyCaption=it},label={Text("Подпись к истории")},modifier=Modifier.fillMaxWidth())
            Button(onClick={info="Выбор фото/видео и публикация будут подключены вместе с Supabase."}) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(6.dp)); Text("Добавить историю") }
        } else {
            Text("Создать канал",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(channelTitle,{channelTitle=it},label={Text("Название")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(channelAbout,{channelAbout=it},label={Text("Описание")},modifier=Modifier.fillMaxWidth())
            Button(onClick={info=if(channelTitle.isBlank()) "Введите название канала." else "Канал «$channelTitle» сохранён как черновик интерфейса. Серверное создание подключим позже."}) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(6.dp)); Text("Создать канал") }
            Spacer(Modifier.height(16.dp))
            Text("Примеры каналов",style=MaterialTheme.typography.titleMedium)
            LazyColumn { items(demoChannels) { item -> ListItem(leadingContent={Icon(Icons.Rounded.Campaign,null)},headlineContent={Text(item)},supportingContent={Text("Демо · подписка будет подключена позже")}) } }
        }
        Spacer(Modifier.height(12.dp))
        Text(info,color=MaterialTheme.colorScheme.secondary)
    }
}
