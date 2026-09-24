package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.BotRunningState
import com.example.data.LogEntry
import com.example.data.ScriptStatus

// Clean, high-performance colors (No lag / No glow)
val BgDark = Color(0xFF0F172A)
val CardDark = Color(0xFF1E293B)
val CardBorder = Color(0xFF334155)
val EmeraldGreen = Color(0xFF10B981)
val CoralRed = Color(0xFFEF4444)
val AmberYellow = Color(0xFFF59E0B)
val SkyBlue = Color(0xFF0EA5E9)
val TextLight = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val ConsoleBg = Color(0xFF020617)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: BotViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var isTokenVisible by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SkyBlue),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = "Logo",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Telegram Bot Host",
                                color = TextLight,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "রানার ও কন্ট্রোল প্যানেল",
                                color = TextMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                actions = {
                    // Status Pill in Top Bar
                    val (statusBg, statusText, textColor) = when (uiState.botRunningState) {
                        BotRunningState.RUNNING -> Triple(EmeraldGreen.copy(alpha = 0.2f), "অনলাইন", EmeraldGreen)
                        BotRunningState.STARTING -> Triple(AmberYellow.copy(alpha = 0.2f), "কানেক্টিং...", AmberYellow)
                        BotRunningState.ERROR -> Triple(CoralRed.copy(alpha = 0.2f), "ত্রুটি", CoralRed)
                        BotRunningState.STOPPED -> Triple(Color.White.copy(alpha = 0.08f), "অফলাইন", TextMuted)
                    }

                    Surface(
                        color = statusBg,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(textColor)
                            )
                            Text(
                                text = statusText,
                                color = textColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CardDark
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // Built-in Requirements Badge
            item {
                RequirementsCard()
            }

            // Server Status Check Card
            item {
                ServerStatusCard(
                    status = uiState.scriptStatus,
                    message = uiState.scriptStatusMessage,
                    lastCheckTime = uiState.lastCheckTime,
                    isChecking = uiState.isCheckingScript,
                    onRefresh = { viewModel.checkRawLink() }
                )
            }

            // Bot Token Input Card
            item {
                BotTokenCard(
                    token = uiState.botToken,
                    isTokenVisible = isTokenVisible,
                    isSaved = uiState.isTokenSaved,
                    isTesting = uiState.isTestingToken,
                    onTokenChange = { viewModel.onTokenChanged(it) },
                    onToggleVisibility = { isTokenVisible = !isTokenVisible },
                    onPaste = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pastedText = clip.getItemAt(0).text?.toString().orEmpty()
                            viewModel.onTokenChanged(pastedText)
                            Toast.makeText(context, "টোকেন পেস্ট করা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onTest = { viewModel.testTokenOnly() }
                )
            }

            // Running Bot Details Card (If Connected)
            if (uiState.botRunningState == BotRunningState.RUNNING && uiState.botInfo != null) {
                item {
                    ConnectedBotCard(
                        botInfo = uiState.botInfo!!,
                        processedCount = uiState.totalProcessedMessages
                    )
                }
            }

            // Main Control Button (Start / Stop)
            item {
                ActionControlButtons(
                    runningState = uiState.botRunningState,
                    scriptStatus = uiState.scriptStatus,
                    hasToken = uiState.botToken.isNotBlank(),
                    onStart = { viewModel.startBot() },
                    onStop = { viewModel.stopBot() }
                )
            }

            // Live Logs Terminal Card
            item {
                LiveLogsCard(
                    logs = uiState.logs,
                    onClear = { viewModel.clearLogs() },
                    onCopy = {
                        val text = uiState.logs.joinToString("\n") { "[${it.timestamp}] ${it.tag}: ${it.message}" }
                        if (text.isNotBlank()) {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Bot Logs", text))
                            Toast.makeText(context, "সকল লগ কপি করা হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun RequirementsCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(text = "📦", fontSize = 18.sp)
            Column {
                Text(
                    text = "বিল্ট-ইন প্যাকেজ (Pre-installed):",
                    color = TextLight,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "python-telegram-bot>=21.0  •  httpx[http2]>=0.27.0  •  h2>=4.1.0",
                    color = SkyBlue,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun ServerStatusCard(
    status: ScriptStatus,
    message: String,
    lastCheckTime: String,
    isChecking: Boolean,
    onRefresh: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "সার্ভার ও স্ক্রিপ্ট স্ট্যাটাস",
                        color = TextLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "স্বয়ংক্রিয় ক্লাউড সিঙ্ক",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                // Refresh Button
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isChecking,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SkyBlue
                    ),
                    modifier = Modifier.height(34.dp)
                ) {
                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = SkyBlue,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "রিফ্রেশ", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status display box
            val (badgeBg, badgeBorder, badgeIcon, statusTitle, titleColor) = when (status) {
                ScriptStatus.OK -> Tuple5(
                    EmeraldGreen.copy(alpha = 0.12f),
                    EmeraldGreen.copy(alpha = 0.4f),
                    Icons.Default.CheckCircle,
                    "Status: OK",
                    EmeraldGreen
                )
                ScriptStatus.FAILED -> Tuple5(
                    CoralRed.copy(alpha = 0.12f),
                    CoralRed.copy(alpha = 0.4f),
                    Icons.Default.Error,
                    "Status: FAILED",
                    CoralRed
                )
                ScriptStatus.CHECKING -> Tuple5(
                    AmberYellow.copy(alpha = 0.12f),
                    AmberYellow.copy(alpha = 0.4f),
                    Icons.Default.Refresh,
                    "Status: CHECKING...",
                    AmberYellow
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(badgeBg)
                    .border(1.dp, badgeBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = badgeIcon,
                        contentDescription = "Status",
                        tint = titleColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = statusTitle,
                                color = titleColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (lastCheckTime.isNotBlank()) {
                                Text(
                                    text = "• $lastCheckTime",
                                    color = TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Text(
                            text = message,
                            color = TextLight,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

@Composable
fun BotTokenCard(
    token: String,
    isTokenVisible: Boolean,
    isSaved: Boolean,
    isTesting: Boolean,
    onTokenChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onPaste: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Key",
                        tint = AmberYellow,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Telegram Bot Token",
                        color = TextLight,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isSaved) {
                    Text(
                        text = "✓ সেভ করা আছে",
                        color = EmeraldGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = token,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "এখানে Bot Token পেস্ট করুন (যেমন: 123456:ABC...)",
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(
                                imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Visibility",
                                tint = TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SkyBlue,
                    unfocusedBorderColor = CardBorder,
                    focusedTextColor = TextLight,
                    unfocusedTextColor = TextLight,
                    cursorColor = SkyBlue,
                    focusedContainerColor = ConsoleBg,
                    unfocusedContainerColor = ConsoleBg
                ),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Paste button
                OutlinedButton(
                    onClick = onPaste,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextLight),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste",
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "পেস্ট করুন", fontSize = 12.sp)
                }

                // Test button
                OutlinedButton(
                    onClick = onTest,
                    enabled = !isTesting && token.isNotBlank(),
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberYellow),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = AmberYellow,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(text = "⚡ টেস্ট করুন", fontSize = 12.sp, color = AmberYellow)
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectedBotCard(botInfo: com.example.data.BotInfo, processedCount: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = EmeraldGreen.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(EmeraldGreen),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🤖", fontSize = 20.sp)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${botInfo.username.ifEmpty { "TelegramBot" }}",
                    color = TextLight,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${botInfo.firstName} (ID: ${botInfo.id})",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$processedCount",
                    color = EmeraldGreen,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "মেসেজ প্রাপ্ত",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
fun ActionControlButtons(
    runningState: BotRunningState,
    scriptStatus: ScriptStatus,
    hasToken: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isRunning = runningState == BotRunningState.RUNNING || runningState == BotRunningState.STARTING

    if (!isRunning) {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = EmeraldGreen,
                disabledContainerColor = CardDark
            ),
            enabled = scriptStatus == ScriptStatus.OK && hasToken
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Start",
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "▶  বট চালু করুন (Start Bot)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    } else {
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "Stop",
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "⏹  বট বন্ধ করুন (Stop Bot)",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun LiveLogsCard(
    logs: List<LogEntry>,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Terminal",
                        tint = SkyBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "লাইভ কনসোল / লগ (${logs.size})",
                        color = TextLight,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Logs",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Clear Logs",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ConsoleBg)
                    .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "// কোনো অ্যাক্টিভিটি নেই। বট স্টার্ট করলে এখানে তথ্য দেখা যাবে।",
                        color = TextMuted.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs, key = { it.id }) { log ->
                            val color = when {
                                log.isError -> CoralRed
                                log.isSuccess -> EmeraldGreen
                                log.isIncoming -> SkyBlue
                                log.isOutgoing -> AmberYellow
                                else -> TextLight
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = log.timestamp,
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "[${log.tag}]",
                                    color = color.copy(alpha = 0.8f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = log.message,
                                    color = color,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
