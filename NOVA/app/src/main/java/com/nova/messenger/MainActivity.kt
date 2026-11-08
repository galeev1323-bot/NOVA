package com.nova.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nova.messenger.data.Graph
import com.nova.messenger.ui.NovaRoot
import com.nova.messenger.ui.NovaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)
        handle(intent)
        setContent { NovaTheme(Graph.settings.theme) { NovaRoot() } }
        // статус «в сети»: обновляем last_seen, пока приложение на экране
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { if (Graph.configured) Graph.chats.touchLastSeen(); delay(30_000) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handle(intent) }
    private fun handle(i: Intent?) { i?.getStringExtra("chat_id")?.let { Graph.pendingChatId = it } }
    override fun onStart() { super.onStart(); Graph.foreground = true }
    override fun onStop() { super.onStop(); Graph.foreground = false }
}
