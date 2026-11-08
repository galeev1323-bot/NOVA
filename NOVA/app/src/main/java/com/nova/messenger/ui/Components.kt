package com.nova.messenger.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nova.messenger.data.Profile
import java.time.*
import java.time.format.DateTimeFormatter

/** Эффект нажатия с масштабированием. */
fun Modifier.pressScale(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.93f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "press"
    )
    this.scale(s).clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun isDarkUi() = MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val brush = if (isDarkUi()) Brush.verticalGradient(listOf(Navy, Ink, Ink))
    else Brush.verticalGradient(listOf(Color(0xFFEDE7FF), Color(0xFFF5F6FB)))
    Box(modifier.fillMaxSize().background(brush), content = content)
}

@Composable
fun NovaButton(text: String, modifier: Modifier = Modifier, loading: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp))
            .background(if (enabled) NovaGradient else Brush.linearGradient(listOf(Color(0xFF3A3F55), Color(0xFF3A3F55))))
            .pressScale(enabled && !loading, onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
fun NovaField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    password: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, leading: ImageVector? = null,
    singleLine: Boolean = true,
) = OutlinedTextField(
    value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(),
    label = { Text(label) }, singleLine = singleLine, shape = RoundedCornerShape(16.dp),
    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
    leadingIcon = leading?.let { { androidx.compose.material3.Icon(it, null) } },
)

fun frameBrush(id: String): Brush? = when (id) {
    "neon" -> NovaGradient
    "gold" -> Brush.linearGradient(listOf(Color(0xFFFFD36E), Color(0xFFFF9F43)))
    "aurora" -> Brush.linearGradient(listOf(Color(0xFF34D399), Cyan, Violet))
    else -> null
}

@Composable
fun Avatar(url: String?, name: String, size: Dp = 48.dp, frame: String = "none", modifier: Modifier = Modifier) {
    val fb = frameBrush(frame)
    val ring = if (fb != null) size * 0.07f else 0.dp
    Box(modifier.size(size).then(if (fb != null) Modifier.border(ring.coerceAtLeast(2.dp), fb, CircleShape) else Modifier).padding(if (fb != null) ring + 2.dp else 0.dp)
        .clip(CircleShape), contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank()) AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize().background(NovaGradient), contentAlignment = Alignment.Center) {
            Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

@Composable
fun ProfileAvatar(p: Profile?, size: Dp = 48.dp) =
    Avatar(p?.avatarUrl, p?.displayName ?: "?", size, if (p?.isPremium == true) p.avatarFrame else "none")

fun initials(n: String) = n.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" }

@Composable
fun PremiumBadge(size: Dp = 16.dp) =
    androidx.compose.material3.Icon(Icons.Rounded.Verified, "Premium", Modifier.size(size), tint = Gold)

@Composable
fun GradientTitle(text: String, size: Int = 28) =
    Text(text, style = TextStyle(brush = NovaGradient, fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp))

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier) =
    Column(modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GradientTitle("✦", 48)
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(6.dp))
        Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }

@Composable
fun Header(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        else Spacer(Modifier.width(12.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }

fun fmtTime(iso: String?, alwaysTime: Boolean = false): String = runCatching {
    val z = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
    if (alwaysTime || z.toLocalDate() == LocalDate.now()) z.format(DateTimeFormatter.ofPattern("HH:mm"))
    else z.format(DateTimeFormatter.ofPattern("dd.MM"))
}.getOrDefault("")

fun statusText(p: Profile?): String {
    if (p == null) return ""
    val t = runCatching { OffsetDateTime.parse(p.lastSeen).toInstant().toEpochMilli() }.getOrNull() ?: return "был(а) недавно"
    return if (!p.hideLastSeen && System.currentTimeMillis() - t < 75_000) "в сети" else "был(а) недавно"
}
