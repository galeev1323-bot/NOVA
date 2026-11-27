package com.nova.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StoriesChannelsScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var channelTitle by rememberSaveable { mutableStateOf("") }
    var channelAbout by rememberSaveable { mutableStateOf("") }
    var storyCaption by rememberSaveable { mutableStateOf("") }
    var info by rememberSaveable { mutableStateOf("Черновики хранятся только в памяти экрана. Серверное размещение подключим позже.") }
    var stories by rememberSaveable { mutableStateOf(listOf<String>()) }
    var channels by rememberSaveable { mutableStateOf(listOf<Pair<String,String>>()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Истории и каналы", style=MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        TabRow(selectedTabIndex=selected) {
            Tab(selected==0,{selected=0},text={Text("Истории")})
            Tab(selected==1,{selected=1},text={Text("Каналы")})
        }
        Spacer(Modifier.height(12.dp))
        if(selected==0) {
            Text("Текстовые черновики историй",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(storyCaption,{storyCaption=it},label={Text("Текст истории")},modifier=Modifier.fillMaxWidth(), minLines=2)
            Button(onClick={
                if(storyCaption.isBlank()) info="Напиши текст истории."
                else { stories=stories+storyCaption.trim(); storyCaption=""; info="Черновик добавлен в список ниже." }
            }) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(6.dp)); Text("Добавить черновик") }
            if(stories.isNotEmpty()) {
                Text("Черновики",style=MaterialTheme.typography.titleMedium)
                LazyColumn(Modifier.weight(1f, fill=false)) { items(stories.reversed()) { item -> ListItem(leadingContent={Icon(Icons.Rounded.AutoStories,null)},headlineContent={Text(item)}) } }
                TextButton(onClick={stories=emptyList()}) { Text("Очистить черновики") }
            }
            Text("Фото, видео и публикация появятся после подключения хранилища.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Создать канал (локальный черновик)",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(channelTitle,{channelTitle=it},label={Text("Название")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(channelAbout,{channelAbout=it},label={Text("Описание")},modifier=Modifier.fillMaxWidth(),minLines=2)
            Button(onClick={
                if(channelTitle.isBlank()) info="Введите название канала."
                else { channels=channels+(channelTitle.trim() to channelAbout.trim()); info="Черновик канала добавлен."; channelTitle=""; channelAbout="" }
            }) { Icon(Icons.Rounded.Add,null); Spacer(Modifier.width(6.dp)); Text("Сохранить черновик") }
            Spacer(Modifier.height(8.dp))
            Text("Мои черновики",style=MaterialTheme.typography.titleMedium)
            if(channels.isEmpty()) Text("Пока нет черновиков каналов.")
            else LazyColumn(Modifier.weight(1f, fill=false)) {
                items(channels.reversed()) { (name,about) -> ListItem(leadingContent={Icon(Icons.Rounded.Campaign,null)},headlineContent={Text(name)},supportingContent={Text(about.ifBlank{"Без описания"})}) }
            }
            if(channels.isNotEmpty()) TextButton(onClick={channels=emptyList()}) { Text("Очистить черновики") }
        }
        Spacer(Modifier.height(12.dp))
        Text(info,color=MaterialTheme.colorScheme.secondary)
    }
}
