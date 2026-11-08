package com.nova.messenger.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nova.messenger.data.Graph
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    var byPhone by mutableStateOf(false)
    var register by mutableStateOf(false)
    var step by mutableStateOf("form") // form | otp | forgot | forgotCode
    var email by mutableStateOf(""); var phone by mutableStateOf("+")
    var password by mutableStateOf(""); var username by mutableStateOf(""); var displayName by mutableStateOf("")
    var code by mutableStateOf(""); var newPassword by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)

    private val id get() = if (byPhone) phone.trim() else email.trim()

    private fun run(block: suspend () -> Unit) {
        error = null; info = null; loading = true
        viewModelScope.launch {
            try { block() } catch (e: Exception) { error = friendly(e) } finally { loading = false }
        }
    }
    private fun friendly(e: Exception): String {
        val m = e.message ?: return "Ошибка"
        return when {
            "Invalid login" in m -> "Неверный логин или пароль"
            "already registered" in m -> "Аккаунт уже существует"
            "Unable to resolve host" in m || "timeout" in m.lowercase() -> "Нет соединения с сервером"
            else -> m.lineSequence().first().take(160)
        }
    }
    private fun validateId(): String? {
        if (byPhone && !Regex("^\\+[1-9][0-9]{7,14}$").matches(id)) return "Введите номер в формате +79991234567"
        if (!byPhone && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(id)) return "Введите корректный email"
        return null
    }

    fun submit() {
        validateId()?.let { error = it; return }
        if (password.length < 8) { error = "Пароль — минимум 8 символов"; return }
        if (register) {
            val u = username.trim().lowercase().removePrefix("@")
            if (!Regex("^[a-z0-9_]{3,32}$").matches(u)) { error = "@username: 3–32 символа, a–z, 0–9, _"; return }
            if (displayName.isBlank()) { error = "Введите имя"; return }
            run {
                Graph.auth.signUp(byPhone, id, password, u, displayName.trim())
                if (Graph.client.auth.currentSessionOrNull() == null) {
                    step = "otp"; info = if (byPhone) "Мы отправили SMS-код на $id" else "Мы отправили код подтверждения на $id"
                }
            }
        } else run { Graph.auth.signIn(byPhone, id, password) }
    }

    fun verify() {
        if (code.length < 4) { error = "Введите код"; return }
        run { Graph.auth.verifySignUp(byPhone, id, code.trim()) }
    }
    fun sendReset() {
        if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())) { error = "Введите email аккаунта"; return }
        run { Graph.auth.sendReset(email.trim()); step = "forgotCode"; info = "Код отправлен на ${email.trim()}" }
    }
    fun confirmReset() {
        if (newPassword.length < 8) { error = "Пароль — минимум 8 символов"; return }
        run { Graph.auth.confirmReset(email.trim(), code.trim(), newPassword) }
    }
    fun back() { step = "form"; error = null; info = null; code = "" }
}

@Composable
fun AuthScreen() {
    val vm: AuthViewModel = viewModel()
    AppBackground {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 460.dp).fillMaxWidth()
                .systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GradientTitle("NOVA", 44)
            Text(
                when (vm.step) { "otp" -> "Подтверждение"; "forgot", "forgotCode" -> "Восстановление пароля"
                    else -> if (vm.register) "Создайте аккаунт" else "С возвращением" },
                fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            )
            AnimatedContent(
                vm.step, label = "authStep",
                transitionSpec = { (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 10 }) togetherWith fadeOut(tween(150)) },
            ) { step ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        "form" -> FormStep(vm)
                        "otp" -> {
                            NovaField(vm.code, { vm.code = it.filter(Char::isDigit).take(8) }, "Код из сообщения", keyboard = KeyboardType.Number, leading = Icons.Rounded.Pin)
                            NovaButton("Подтвердить", loading = vm.loading) { vm.verify() }
                            TextButton({ vm.back() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Назад") }
                        }
                        "forgot" -> {
                            NovaField(vm.email, { vm.email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Rounded.Email)
                            NovaButton("Отправить код", loading = vm.loading) { vm.sendReset() }
                            TextButton({ vm.back() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Назад") }
                        }
                        else -> {
                            NovaField(vm.code, { vm.code = it.filter(Char::isDigit).take(8) }, "Код из письма", keyboard = KeyboardType.Number, leading = Icons.Rounded.Pin)
                            NovaField(vm.newPassword, { vm.newPassword = it }, "Новый пароль", password = true, leading = Icons.Rounded.Lock)
                            NovaButton("Сменить пароль", loading = vm.loading) { vm.confirmReset() }
                            TextButton({ vm.back() }, Modifier.align(Alignment.CenterHorizontally)) { Text("Назад") }
                        }
                    }
                }
            }
            AnimatedVisibility(vm.error != null) { Text(vm.error ?: "", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            AnimatedVisibility(vm.info != null) { Text(vm.info ?: "", color = Cyan, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun ColumnScope.FormStep(vm: AuthViewModel) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
    ) {
        listOf("Email" to false, "Телефон" to true).forEach { (label, phone) ->
            val sel = vm.byPhone == phone
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .pressScale { vm.byPhone = phone; vm.error = null }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold) }
        }
    }
    if (vm.register) {
        NovaField(vm.displayName, { vm.displayName = it }, "Имя", leading = Icons.Rounded.Person)
        NovaField(vm.username, { vm.username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' || c == '@' } }, "@username", leading = Icons.Rounded.AlternateEmail)
    }
    if (vm.byPhone) NovaField(vm.phone, { vm.phone = it.filter { c -> c.isDigit() || c == '+' } }, "Телефон", keyboard = KeyboardType.Phone, leading = Icons.Rounded.Phone)
    else NovaField(vm.email, { vm.email = it }, "Email", keyboard = KeyboardType.Email, leading = Icons.Rounded.Email)
    NovaField(vm.password, { vm.password = it }, "Пароль", password = true, leading = Icons.Rounded.Lock)
    NovaButton(if (vm.register) "Зарегистрироваться" else "Войти", loading = vm.loading) { vm.submit() }
    if (!vm.register) TextButton({ vm.step = "forgot"; vm.byPhone = false; vm.error = null }, Modifier.align(Alignment.CenterHorizontally)) { Text("Забыли пароль?") }
    TextButton({ vm.register = !vm.register; vm.error = null }, Modifier.align(Alignment.CenterHorizontally)) {
        Text(if (vm.register) "Уже есть аккаунт? Войти" else "Нет аккаунта? Зарегистрироваться")
    }
}
