package com.nova.messenger.data

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.android.billingclient.api.*
import com.nova.messenger.BuildConfig
import com.nova.messenger.MainActivity
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

object Graph {
    lateinit var app: Application
    val configured get() = !BuildConfig.SUPABASE_URL.contains("YOUR_PROJECT")
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val client: SupabaseClient by lazy {
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) {
            install(Auth) { sessionManager = EncryptedSessionManager(app) }
            install(Postgrest)
            install(Realtime)
            install(Storage)
            install(Functions)
        }
    }
    val db: NovaDb by lazy {
        Room.databaseBuilder(app, NovaDb::class.java, "nova.db").fallbackToDestructiveMigration().build()
    }
    val settings by lazy { SettingsStore(app) }
    val auth by lazy { AuthRepo(client) }
    val chats by lazy { ChatRepo(client, db) }

    var pendingChatId by mutableStateOf<String?>(null)
    @Volatile var foreground = false
}

/** Токены сессии хранятся в EncryptedSharedPreferences (AES-256, ключ в Android Keystore). */
class EncryptedSessionManager(ctx: Context) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = EncryptedSharedPreferences.create(
        ctx, "nova_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override suspend fun saveSession(session: UserSession) {
        prefs.edit().putString("session", json.encodeToString(session)).apply()
    }
    override suspend fun loadSession(): UserSession? =
        prefs.getString("session", null)?.let { runCatching { json.decodeFromString<UserSession>(it) }.getOrNull() }
    override suspend fun deleteSession() { prefs.edit().remove("session").apply() }
}

class SettingsStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)
    var theme by mutableStateOf(p.getString("theme", "dark") ?: "dark"); private set
    var notifications by mutableStateOf(p.getBoolean("notif", true)); private set
    var preview by mutableStateOf(p.getBoolean("preview", true)); private set
    fun updateTheme(v: String) { theme = v; p.edit().putString("theme", v).apply() }
    fun updateNotifications(v: Boolean) { notifications = v; p.edit().putBoolean("notif", v).apply() }
    fun updatePreview(v: Boolean) { preview = v; p.edit().putBoolean("preview", v).apply() }
}

object Notifier {
    private const val CH = "messages"
    fun init(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Сообщения", NotificationManager.IMPORTANCE_HIGH))
        }
    }
    @SuppressLint("MissingPermission")
    fun show(ctx: Context, title: String, text: String, chatId: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chat_id", chatId)
        }
        val pi = PendingIntent.getActivity(ctx, chatId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        nm.notify(chatId.hashCode(), n)
    }
}

class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    fun start() {
        file = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        rec = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file!!.absolutePath)
            prepare(); start()
        }
    }
    fun stop(): File? {
        runCatching { rec?.stop() }
        rec?.release(); rec = null
        return file
    }
}

object AudioPlayer {
    private var mp: MediaPlayer? = null
    var playing by mutableStateOf<String?>(null)
    fun toggle(url: String) {
        if (playing == url) { stop(); return }
        stop()
        mp = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepareAsync()
        }
        playing = url
    }
    fun stop() { runCatching { mp?.release() }; mp = null; playing = null }
}

/** Google Play Billing. Покупка проверяется на СЕРВЕРЕ (Edge Function), клиент Premium не выдаёт. */
class BillingManager(ctx: Context, private val onMessage: (String) -> Unit) : PurchasesUpdatedListener {
    private val bc = BillingClient.newBuilder(ctx).setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()).build()

    private fun withConnection(block: () -> Unit) {
        if (bc.isReady) { block(); return }
        bc.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) block()
                else onMessage("Google Play Billing недоступен на этом устройстве")
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun buy(activity: Activity) = withConnection {
        val q = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(BuildConfig.PREMIUM_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS).build())).build()
        bc.queryProductDetailsAsync(q) { r, list ->
            val d = list.firstOrNull()
            val token = d?.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (r.responseCode != BillingClient.BillingResponseCode.OK || d == null || token == null) {
                onMessage("Подписка «${BuildConfig.PREMIUM_PRODUCT_ID}» не найдена в Google Play Console")
                return@queryProductDetailsAsync
            }
            val p = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(token).build()
            bc.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(p)).build())
        }
    }

    override fun onPurchasesUpdated(r: BillingResult, purchases: MutableList<Purchase>?) {
        if (r.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) return
        if (r.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) {
            onMessage("Покупка не завершена"); return
        }
        purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { p ->
            Graph.appScope.launch {
                runCatching {
                    val resp = Graph.client.functions.invoke("verify-play-purchase", buildJsonObject {
                        put("purchaseToken", p.purchaseToken)
                        put("productId", BuildConfig.PREMIUM_PRODUCT_ID)
                    })
                    if (resp.status.value !in 200..299) error("Сервер не подтвердил подписку")
                    bc.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) {}
                    Graph.chats.loadMe()
                    onMessage("Premium активирован")
                }.onFailure { onMessage(it.message ?: "Ошибка проверки покупки") }
            }
        }
    }
}
