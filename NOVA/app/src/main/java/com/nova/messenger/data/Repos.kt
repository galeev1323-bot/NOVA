package com.nova.messenger.data

import android.net.Uri
import android.provider.OpenableColumns
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.Phone
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.util.UUID

class AuthRepo(private val c: SupabaseClient) {
    private fun meta(u: String, n: String) = buildJsonObject { put("username", u); put("display_name", n) }

    suspend fun usernameAvailable(u: String): Boolean =
        c.postgrest.rpc("username_available", buildJsonObject { put("u", u) }).decodeAs<Boolean>()

    suspend fun signUp(byPhone: Boolean, id: String, pw: String, username: String, name: String) {
        if (!usernameAvailable(username)) error("Этот @username уже занят")
        if (byPhone) c.auth.signUpWith(Phone) { phone = id; password = pw; data = meta(username, name) }
        else c.auth.signUpWith(Email) { email = id; password = pw; data = meta(username, name) }
    }

    suspend fun signIn(byPhone: Boolean, id: String, pw: String) {
        if (byPhone) c.auth.signInWith(Phone) { phone = id; password = pw }
        else c.auth.signInWith(Email) { email = id; password = pw }
    }

    suspend fun verifySignUp(byPhone: Boolean, id: String, code: String) {
        if (byPhone) c.auth.verifyPhoneOtp(type = OtpType.Phone.SMS, phone = id, token = code)
        else c.auth.verifyEmailOtp(type = OtpType.Email.SIGNUP, email = id, token = code)
    }

    suspend fun sendReset(email: String) = c.auth.resetPasswordForEmail(email)

    suspend fun confirmReset(email: String, code: String, newPassword: String) {
        c.auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = email, token = code)
        c.auth.updateUser { password = newPassword }
    }

    suspend fun changePassword(pw: String) { c.auth.updateUser { password = pw } }

    suspend fun signOut() {
        Graph.chats.stop()
        c.auth.signOut()
        withContext(Dispatchers.IO) { Graph.db.clearAllTables() }
    }
}

class UploadResult(val url: String, val type: String, val name: String)

class ChatRepo(private val c: SupabaseClient, private val db: NovaDb) {
    private val dao = db.dao()
    private val scope get() = Graph.appScope
    val me = MutableStateFlow<Profile?>(null)
    val isOwnerOrAdmin = MutableStateFlow(false)
    val starsBalance = MutableStateFlow(0L)
    @Volatile var openChatId: String? = null
    val myId: String? get() = c.auth.currentUserOrNull()?.id
    private var channel: io.github.jan.supabase.realtime.RealtimeChannel? = null
    private val jobs = mutableListOf<Job>()

    // ---------- lifecycle ----------
    fun start() {
        if (channel != null) return
        scope.launch { runCatching { loadMe() }; runCatching { refreshChats() } }
        val ch = c.channel("nova-db")
        channel = ch
        val m = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "messages" }
        val r = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "reactions" }
        val cm = ch.postgresChangeFlow<PostgresAction>(schema = "public") { table = "chat_members" }
        jobs += scope.launch { runCatching { ch.subscribe() } }
        jobs += m.onEach { a ->
            runCatching {
                when (a) {
                    is PostgresAction.Insert -> { val msg = a.decodeRecord<Message>(); dao.upsertMessages(listOf(msg)); onIncoming(msg) }
                    is PostgresAction.Update -> dao.upsertMessages(listOf(a.decodeRecord<Message>()))
                    is PostgresAction.Delete -> a.oldRecord["id"]?.jsonPrimitive?.contentOrNull?.let { dao.deleteMessage(it) }
                    else -> {}
                }
            }
        }.launchIn(scope)
        jobs += r.onEach { a ->
            runCatching {
                when (a) {
                    is PostgresAction.Insert -> dao.upsertReactions(listOf(a.decodeRecord<Reaction>()))
                    is PostgresAction.Update -> dao.upsertReactions(listOf(a.decodeRecord<Reaction>()))
                    is PostgresAction.Delete -> {
                        val mid = a.oldRecord["message_id"]?.jsonPrimitive?.contentOrNull
                        val uid = a.oldRecord["user_id"]?.jsonPrimitive?.contentOrNull
                        if (mid != null && uid != null) dao.deleteReaction(mid, uid)
                    }
                    else -> {}
                }
            }
        }.launchIn(scope)
        jobs += cm.onEach { runCatching { refreshChats() } }.launchIn(scope)
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        val ch = channel; channel = null; me.value = null
        if (ch != null) scope.launch { runCatching { c.realtime.removeChannel(ch) } }
    }

    private suspend fun onIncoming(m: Message) {
        if (m.senderId == myId) return
        if (openChatId == m.chatId && Graph.foreground) { markRead(m.chatId); return }
        refreshChats()
        if (Graph.settings.notifications) {
            val title = dao.chatOnce(m.chatId)?.title ?: "Новое сообщение"
            val text = if (Graph.settings.preview) (m.body ?: "[вложение]") else "Новое сообщение"
            Notifier.show(Graph.app, title, text, m.chatId)
        }
    }

    // ---------- profile ----------
    suspend fun loadMe() {
        if (myId == null) {
            me.value = null
            isOwnerOrAdmin.value = false
            starsBalance.value = 0L
            return
        }
        me.value = profile(myId!!)
        refreshStaffAccess()
        refreshStars()
    }

    suspend fun refreshStaffAccess() {
        isOwnerOrAdmin.value = runCatching {
            c.postgrest.rpc("is_owner_or_admin").decodeAs<Boolean>()
        }.getOrDefault(false)
    }

    suspend fun refreshStars() {
        starsBalance.value = runCatching {
            c.postgrest.rpc("get_my_stars").decodeAs<Long>()
        }.getOrDefault(0L)
    }

    suspend fun grantStars(userId: String, amount: Long, reason: String) {
        require(amount in 1L..1_000_000L) { "Количество звёзд должно быть от 1 до 1 000 000" }
        require(reason.trim().isNotEmpty()) { "Укажи причину начисления" }
        check(isOwnerOrAdmin.value) { "Недостаточно прав" }
        c.postgrest.rpc("grant_stars", buildJsonObject {
            put("p_user_id", userId)
            put("p_amount", amount)
            put("p_reason", reason.trim())
        })
        refreshStars()
    }

    suspend fun grantPremium(userId: String, days: Int) {
        require(days in listOf(0, 7, 30, 90, 180, 365)) { "Недопустимый срок Premium" }
        check(isOwnerOrAdmin.value) { "Недостаточно прав" }
        c.postgrest.rpc("grant_premium", buildJsonObject {
            put("p_user_id", userId)
            put("p_days", days)
        })
    }
    suspend fun profile(id: String): Profile? =
        c.from("profiles").select { filter { eq("id", id) } }.decodeSingleOrNull<Profile>()
    suspend fun profiles(ids: List<String>): List<Profile> =
        if (ids.isEmpty()) emptyList() else c.from("profiles").select { filter { isIn("id", ids) } }.decodeList<Profile>()

    suspend fun updateProfile(b: JsonObjectBuilder.() -> Unit) {
        val id = myId ?: return
        c.from("profiles").update(buildJsonObject(b)) { filter { eq("id", id) } }
        loadMe()
    }
    suspend fun touchLastSeen() { runCatching { updateProfileQuiet() } }
    private suspend fun updateProfileQuiet() {
        val id = myId ?: return
        c.from("profiles").update(buildJsonObject { put("last_seen", Instant.now().toString()) }) { filter { eq("id", id) } }
    }

    // ---------- chats ----------
    suspend fun refreshChats() {
        val list = c.postgrest.rpc("get_my_chats").decodeList<Chat>()
        dao.upsertChats(list)
        if (list.isEmpty()) db.clearAllTables() else dao.pruneChats(list.map { it.id })
    }

    suspend fun openDm(userId: String): String =
        c.postgrest.rpc("get_or_create_dm", buildJsonObject { put("other", userId) }).decodeAs<String>().also { refreshChats() }

    suspend fun createGroup(title: String, members: List<String>): String =
        c.postgrest.rpc("create_group", buildJsonObject {
            put("title", title); put("members", JsonArray(members.map { JsonPrimitive(it) }))
        }).decodeAs<String>().also { refreshChats() }

    // ---------- messages ----------
    suspend fun loadMessages(chatId: String) {
        val ms = c.from("messages").select {
            filter { eq("chat_id", chatId) }; order("created_at", Order.DESCENDING); limit(150)
        }.decodeList<Message>()
        dao.upsertMessages(ms)
        if (ms.isNotEmpty()) {
            val rs = c.from("reactions").select { filter { isIn("message_id", ms.map { it.id }) } }.decodeList<Reaction>()
            dao.upsertReactions(rs)
        }
    }

    suspend fun send(chatId: String, body: String?, replyTo: String? = null, mediaUrl: String? = null,
                     mediaType: String? = null, mediaName: String? = null, forwarded: Boolean = false) {
        val m = c.from("messages").insert(MessageInsert(chatId, body, mediaUrl, mediaType, mediaName, replyTo, forwarded)) {
            select()
        }.decodeSingle<Message>()
        dao.upsertMessages(listOf(m))
        refreshChats()
    }

    suspend fun edit(id: String, text: String) {
        c.from("messages").update({ set("body", text); set("edited_at", Instant.now().toString()) }) { filter { eq("id", id) } }
    }
    suspend fun delete(id: String) {
        c.from("messages").delete { filter { eq("id", id) } }
        dao.deleteMessage(id)
    }
    suspend fun toggleReaction(messageId: String, emoji: String, current: String?) {
        val uid = myId ?: return
        if (current == emoji) {
            c.from("reactions").delete { filter { eq("message_id", messageId); eq("user_id", uid) } }
            dao.deleteReaction(messageId, uid)
        } else {
            val r = Reaction(messageId, uid, emoji)
            c.from("reactions").upsert(r)
            dao.upsertReactions(listOf(r))
        }
    }
    suspend fun markRead(chatId: String) {
        val uid = myId ?: return
        dao.clearUnread(chatId)
        c.from("chat_members").update({ set("last_read_at", Instant.now().toString()) }) {
            filter { eq("chat_id", chatId); eq("user_id", uid) }
        }
    }

    // ---------- search ----------
    private fun clean(q: String) = q.replace(Regex("[%,()*]"), " ").trim()
    suspend fun searchUsers(q: String): List<Profile> {
        val s = clean(q); if (s.length < 2) return emptyList()
        val uid = myId ?: return emptyList()
        return c.from("profiles").select {
            filter { or { ilike("username", "%$s%"); ilike("display_name", "%$s%") }; neq("id", uid) }
            limit(30)
        }.decodeList<Profile>()
    }
    suspend fun searchMessages(q: String): List<Message> {
        val s = clean(q); if (s.length < 2) return emptyList()
        return c.from("messages").select {
            filter { ilike("body", "%$s%") }; order("created_at", Order.DESCENDING); limit(30)
        }.decodeList<Message>()
    }

    // ---------- blocks ----------
    suspend fun block(id: String) { c.from("blocks").insert(buildJsonObject { put("blocker_id", myId!!); put("blocked_id", id) }) }
    suspend fun unblock(id: String) { c.from("blocks").delete { filter { eq("blocker_id", myId!!); eq("blocked_id", id) } } }
    suspend fun blocked(): List<Profile> {
        val ids = c.from("blocks").select(Columns.list("blocked_id")) { filter { eq("blocker_id", myId!!) } }
            .decodeList<BlockRow>().map { it.blockedId }
        return profiles(ids)
    }

    // ---------- media ----------
    private fun limitMb() = if (me.value?.isPremium == true) 50 else 10

    suspend fun upload(uri: Uri): UploadResult = withContext(Dispatchers.IO) {
        val cr = Graph.app.contentResolver
        var name = "file"; var size = 0L
        cr.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
            }
        }
        if (size > limitMb() * 1024L * 1024L)
            error("Файл больше ${limitMb()} МБ" + if (me.value?.isPremium != true) ". Premium увеличивает лимит до 50 МБ" else "")
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать файл")
        uploadBytes(bytes, name, mime)
    }

    suspend fun uploadFile(f: File, mime: String): UploadResult = withContext(Dispatchers.IO) { uploadBytes(f.readBytes(), f.name, mime) }

    private suspend fun uploadBytes(bytes: ByteArray, name: String, mime: String): UploadResult {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val path = "${myId}/${UUID.randomUUID()}_$safe"
        val bucket = c.storage.from("media")
        bucket.upload(path, bytes) { upsert = false }
        val type = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("video/") -> "video"
            mime.startsWith("audio/") -> "voice"
            else -> "file"
        }
        return UploadResult(bucket.publicUrl(path), type, name)
    }

    suspend fun uploadAvatar(uri: Uri) {
        val cr = Graph.app.contentResolver
        val mime = cr.getType(uri) ?: ""
        if (!mime.startsWith("image/")) error("Выберите изображение")
        if (mime == "image/gif" && me.value?.isPremium != true) error("Анимированные аватары доступны в NOVA Premium")
        val r = upload(uri)
        updateProfile { put("avatar_url", r.url) }
    }
}
