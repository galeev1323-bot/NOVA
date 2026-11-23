package com.nova.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.nova.messenger.data.ChessGame
import com.nova.messenger.data.Graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ChessScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val myId = Graph.chats.myId
    var username by remember { mutableStateOf("") }
    var games by remember { mutableStateOf<List<ChessGame>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        games = Graph.chats.myChessGames()
    }

    LaunchedEffect(Unit) {
        runCatching { refresh() }.onFailure { message = it.message ?: "Не удалось загрузить партии" }
        while (true) {
            delay(2500)
            runCatching { refresh() }
        }
    }

    val game = games.firstOrNull { it.id == selectedId }
    var localFen by remember(selectedId) { mutableStateOf(game?.fen ?: "") }
    LaunchedEffect(game?.fen) {
        if (game != null && !busy) localFen = game.fen
    }
    val board = remember(localFen) {
        Board().apply { if (localFen.isNotBlank()) loadFromFen(localFen) }
    }
    var selectedSquare by remember(selectedId, localFen) { mutableStateOf<Square?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header("Шахматы", onBack = { nav.popBackStack() })
        if (game == null) {
            Text("Пригласи друга по @username или открой существующую партию.")
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.removePrefix("@").lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(32) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("@username соперника") },
                singleLine = true,
            )
            Button(
                onClick = {
                    if (username.length !in 3..32) { message = "Введи корректный @username"; return@Button }
                    busy = true
                    scope.launch {
                        runCatching { Graph.chats.createChessGame(username) }
                            .onSuccess { id -> message = "Приглашение отправлено"; username = ""; refresh(); selectedId = id }
                            .onFailure { message = it.message ?: "Не удалось создать партию" }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Создаём…" else "Пригласить в шахматы") }

            Text("Мои партии", style = MaterialTheme.typography.titleLarge)
            if (games.isEmpty()) Text("Пока партий нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
            games.forEach { item ->
                val opponent = if (item.whitePlayer == myId) item.blackPlayer else item.whitePlayer
                Card(Modifier.fillMaxWidth().clickable { selectedId = item.id; localFen = item.fen }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Партия · ${item.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                        Text("Соперник: ${opponent.take(8)}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chessStatus(item.status))
                        if (item.status == "pending" && item.blackPlayer == myId) {
                            Button(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        runCatching { Graph.chats.acceptChessGame(item.id); refresh(); selectedId = item.id }
                                            .onFailure { message = it.message ?: "Не удалось принять приглашение" }
                                        busy = false
                                    }
                                },
                                enabled = !busy,
                            ) { Text("Принять вызов") }
                        }
                    }
                }
            }
        } else {
            Text("Партия · ${game.id.take(8)}", style = MaterialTheme.typography.titleLarge)
            Text(chessStatus(game.status), color = MaterialTheme.colorScheme.primary)
            if (game.status == "pending") {
                Text(if (game.blackPlayer == myId) "Тебя пригласили. Прими партию в списке." else "Ожидаем, пока соперник примет приглашение.")
                OutlinedButton(onClick = { selectedId = null }) { Text("К списку партий") }
            } else if (game.status == "active") {
                Text(if (board.sideToMove == Side.WHITE) "Ход белых" else "Ход чёрных")
                if ((board.sideToMove == Side.WHITE && game.whitePlayer == myId) ||
                    (board.sideToMove == Side.BLACK && game.blackPlayer == myId)) {
                    Text("Твой ход", color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("Сейчас ход соперника", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (rank in 8 downTo 1) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (file in 'A'..'H') {
                                val square = Square.valueOf("$file$rank")
                                val piece = board.getPiece(square)
                                val isSelected = selectedSquare == square
                                val isLegalTarget = selectedSquare?.let { from ->
                                    board.legalMoves().any { it.from == from && it.to == square }
                                } ?: false
                                val light = ((rank + (file - 'A')) % 2 == 0)
                                val base = if (light) Color(0xFFE8DCC7) else Color(0xFF78936A)
                                val fill = when {
                                    isSelected -> Color(0xFFFFD54F)
                                    isLegalTarget -> Color(0xFF9BCB8C)
                                    else -> base
                                }
                                Box(
                                    Modifier.weight(1f).aspectRatio(1f)
                                        .background(fill, RoundedCornerShape(3.dp))
                                        .then(if (isSelected) Modifier.border(2.dp, Color(0xFF7A5A00), RoundedCornerShape(3.dp)) else Modifier)
                                        .clickable {
                                            if (busy) return@clickable
                                            val from = selectedSquare
                                            if (from == null) {
                                                val ownPiece = piece != Piece.NONE &&
                                                    ((board.sideToMove == Side.WHITE && piece.getPieceSide() == Side.WHITE) ||
                                                     (board.sideToMove == Side.BLACK && piece.getPieceSide() == Side.BLACK))
                                                if (ownPiece) selectedSquare = square
                                            } else {
                                                val candidates = board.legalMoves().filter { it.from == from && it.to == square }
                                                val move = candidates.firstOrNull { it.promotion == Piece.WHITE_QUEEN || it.promotion == Piece.BLACK_QUEEN }
                                                    ?: candidates.firstOrNull()
                                                if (move != null) {
                                                    val oldFen = localFen
                                                    val nextBoard = Board().apply { loadFromFen(oldFen) }
                                                    if (nextBoard.isMoveLegal(move, true)) {
                                                        nextBoard.doMove(move)
                                                        val nextFen = nextBoard.fen
                                                        val result = when {
                                                            nextBoard.isMated() -> if (nextBoard.sideToMove == Side.WHITE) "black_won" else "white_won"
                                                            nextBoard.isDraw || nextBoard.isStaleMate -> "draw"
                                                            else -> "active"
                                                        }
                                                        selectedSquare = null
                                                        busy = true
                                                        scope.launch {
                                                            runCatching {
                                                                Graph.chats.submitChessMove(game.id, oldFen, nextFen, move.toString(), result)
                                                                localFen = nextFen
                                                                refresh()
                                                            }.onFailure { message = it.message ?: "Ход не сохранён. Обнови партию." }
                                                            busy = false
                                                        }
                                                    }
                                                } else if (piece != Piece.NONE &&
                                                    ((board.sideToMove == Side.WHITE && piece.getPieceSide() == Side.WHITE) ||
                                                     (board.sideToMove == Side.BLACK && piece.getPieceSide() == Side.BLACK))) {
                                                    selectedSquare = square
                                                } else selectedSquare = null
                                            }
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(piece.fanSymbol, color = if (piece == Piece.NONE) Color.Transparent else Color(0xFF161616),
                                        style = MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                    }
                }
                Text("Выбор пешки на последней горизонтали автоматически превращается в ферзя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { Graph.chats.resignChessGame(game.id); refresh() }
                                .onFailure { message = it.message ?: "Не удалось завершить партию" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сдаться") }
            } else {
                Text("Партия завершена.")
                OutlinedButton(onClick = { selectedId = null }) { Text("К списку партий") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun chessStatus(status: String): String = when (status) {
    "pending" -> "Ожидает ответа"
    "active" -> "Идёт партия"
    "white_won" -> "Победили белые"
    "black_won" -> "Победили чёрные"
    "draw" -> "Ничья"
    "cancelled" -> "Отменена"
    else -> status
}
