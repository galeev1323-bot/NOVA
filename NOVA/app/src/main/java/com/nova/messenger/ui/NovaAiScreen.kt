package com.nova.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NovaAiScreen(onBack: () -> Unit) {
    var prompt by rememberSaveable { mutableStateOf("") }
    var history by rememberSaveable { mutableStateOf(listOf<Pair<String,String>>()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Header("NOVA AI", onBack=onBack)
        Text("Демонстрационный экран. Настоящая AI-модель пока не подключена.", color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(history) { (q,a) ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("Запрос",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold); Text(q); HorizontalDivider(Modifier.padding(vertical=8.dp)); Text(a) } }
            }
        }
        OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("Напиши запрос")},minLines=2)
        Button(onClick={
            if(prompt.isNotBlank()) {
                history=history+(prompt.trim() to "Демонстрация интерфейса: подключение настоящего AI-сервиса запланировано позднее.")
                prompt=""
            }
        }, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Send,null); Spacer(Modifier.width(8.dp)); Text("Отправить") }
    }
}