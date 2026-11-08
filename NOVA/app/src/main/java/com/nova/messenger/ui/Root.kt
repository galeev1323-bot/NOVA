package com.nova.messenger.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHostController
import com.nova.messenger.data.Graph
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NovaRoot() {
    if (!Graph.configured) { SetupNeeded(); return }
    var splashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1800); splashDone = true }
    val status by Graph.client.auth.sessionStatus.collectAsState()
    val stage = when {
        !splashDone || status is SessionStatus.Initializing -> "splash"
        status is SessionStatus.Authenticated -> "main"
        else -> "auth"
    }
    AnimatedContent(
        stage, label = "stage",
        transitionSpec = { (fadeIn(tween(500)) + scaleIn(tween(500), initialScale = 0.96f)) togetherWith fadeOut(tween(300)) },
    ) { s ->
        when (s) {
            "splash" -> SplashScreen()
            "auth" -> AuthScreen()
            else -> AppBackground { Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { MainNav() } } }
        }
    }
}

@Composable
fun SetupNeeded() = AppBackground {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center) {
        GradientTitle("NOVA", 40)
        Spacer(Modifier.height(16.dp))
        Text("Backend не настроен", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Создайте файл local.properties в корне проекта и укажите supabase.url и supabase.anon.key " +
            "(см. README.md), затем пересоберите приложение.")
    }
}

@Composable
fun SplashScreen() {
    val inf = rememberInfiniteTransition(label = "splash")
    val rot by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "rot")
    val scale = remember { Animatable(0.5f) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 90f)) }
        alpha.animateTo(1f, tween(700))
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Ink))), Alignment.Center) {
        Canvas(Modifier.size(190.dp).scale(scale.value).rotate(rot)) {
            drawArc(
                brush = Brush.sweepGradient(listOf(Violet, Cyan, Violet)),
                startAngle = 0f, sweepAngle = 270f, useCenter = false,
                style = Stroke(8.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Column(Modifier.alpha(alpha.value).scale(scale.value), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NOVA", style = TextStyle(brush = NovaGradient, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp))
            Text("messenger", color = androidx.compose.ui.graphics.Color(0xFF9AA0B8), letterSpacing = 4.sp, fontSize = 13.sp)
        }
    }
}

@Composable
fun MainNav() {
    val nav = rememberNavController()
    LaunchedEffect(Unit) { Graph.chats.start() }
    val pending = Graph.pendingChatId
    LaunchedEffect(pending) { pending?.let { nav.navigate("chat/$it"); Graph.pendingChatId = null } }
    NavHost(
        nav, "tabs",
        enterTransition = { slideInHorizontally(tween(320)) { it / 3 } + fadeIn(tween(320)) },
        exitTransition = { slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(200)) },
        popEnterTransition = { slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320)) },
        popExitTransition = { slideOutHorizontally(tween(320)) { it / 3 } + fadeOut(tween(200)) },
    ) {
        composable("tabs") { TabsScreen(nav) }
        composable("chat/{id}") { ChatScreen(it.arguments!!.getString("id")!!, nav) }
        composable("settings/{page}") { SettingsScreen(it.arguments!!.getString("page")!!, nav) }
    }
}

private data class TabItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun TabsScreen(nav: NavHostController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val items = listOf(
        TabItem("Чаты", Icons.Rounded.ChatBubble), TabItem("Контакты", Icons.Rounded.People),
        TabItem("Premium", Icons.Rounded.WorkspacePremium), TabItem("Профиль", Icons.Rounded.Person),
    )
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                items.forEachIndexed { i, it ->
                    val sc by animateFloatAsState(if (tab == i) 1.2f else 1f, spring(0.5f, 400f), label = "tabScale")
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(it.icon, it.label, Modifier.scale(sc)) },
                        label = { Text(it.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            tab, Modifier.padding(pad), label = "tabs",
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(300)) { dir * it / 4 } + fadeIn(tween(300))) togetherWith
                    (slideOutHorizontally(tween(300)) { -dir * it / 4 } + fadeOut(tween(150)))
            },
        ) { t ->
            when (t) { 0 -> ChatsTab(nav); 1 -> ContactsTab(nav); 2 -> PremiumTab(); else -> ProfileTab(nav) }
        }
    }
}
