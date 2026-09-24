package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BotForegroundServiceUtils
import com.example.data.BotInfo
import com.example.data.BotRunningState
import com.example.data.BotStateRepository
import com.example.data.LogEntry
import com.example.data.ScriptFetcher
import com.example.data.ScriptStatus
import com.example.data.TelegramEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BotUiState(
    val botToken: String = "",
    val scriptStatus: ScriptStatus = ScriptStatus.CHECKING,
    val scriptStatusMessage: String = "সার্ভার ও কনফিগারেশন চেক হচ্ছে...",
    val rawScriptContent: String = "",
    val isCheckingScript: Boolean = false,
    val botRunningState: BotRunningState = BotRunningState.STOPPED,
    val botInfo: BotInfo? = null,
    val logs: List<LogEntry> = emptyList(),
    val totalProcessedMessages: Int = 0,
    val lastCheckTime: String = "",
    val isTestingToken: Boolean = false,
    val isTokenSaved: Boolean = false
)

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val isTestingTokenFlow = MutableStateFlow(false)

    init {
        BotStateRepository.init(application)
        checkRawLink()
    }

    val uiState: StateFlow<BotUiState> = combine(
        BotStateRepository.botToken,
        BotStateRepository.scriptStatus,
        BotStateRepository.scriptStatusMessage,
        BotStateRepository.rawScriptContent,
        BotStateRepository.isCheckingScript,
        BotStateRepository.botRunningState,
        BotStateRepository.botInfo,
        BotStateRepository.logs,
        BotStateRepository.totalProcessedMessages,
        BotStateRepository.lastCheckTime,
        isTestingTokenFlow
    ) { params: Array<Any?> ->
        val token = params[0] as String
        val scriptStatus = params[1] as ScriptStatus
        val scriptStatusMsg = params[2] as String
        val rawScript = params[3] as String
        val isChecking = params[4] as Boolean
        val runningState = params[5] as BotRunningState
        val botInfo = params[6] as BotInfo?
        @Suppress("UNCHECKED_CAST")
        val logs = params[7] as List<LogEntry>
        val msgCount = params[8] as Int
        val lastCheckTime = params[9] as String
        val isTesting = params[10] as Boolean

        BotUiState(
            botToken = token,
            scriptStatus = scriptStatus,
            scriptStatusMessage = scriptStatusMsg,
            rawScriptContent = rawScript,
            isCheckingScript = isChecking,
            botRunningState = runningState,
            botInfo = botInfo,
            logs = logs,
            totalProcessedMessages = msgCount,
            lastCheckTime = lastCheckTime,
            isTestingToken = isTesting,
            isTokenSaved = token.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = BotUiState(
            botToken = BotStateRepository.getSavedBotToken(),
            isTokenSaved = BotStateRepository.getSavedBotToken().isNotBlank()
        )
    )

    fun onTokenChanged(newToken: String) {
        // Instantly save to permanent local storage
        BotStateRepository.saveBotToken(newToken)
    }

    fun checkRawLink() {
        viewModelScope.launch {
            BotStateRepository.isCheckingScript.value = true
            BotStateRepository.scriptStatus.value = ScriptStatus.CHECKING
            BotStateRepository.scriptStatusMessage.value = "সার্ভার ও কনফিগারেশন চেক হচ্ছে..."
            BotStateRepository.addLog("সিস্টেম", "সার্ভার সংযোগ যাচাই করা হচ্ছে...", isIncoming = false)

            val (status, result) = ScriptFetcher.fetchAndValidateScript()
            val nowTime = timeFormat.format(Date())

            when (status) {
                ScriptStatus.OK -> {
                    BotStateRepository.scriptStatus.value = ScriptStatus.OK
                    BotStateRepository.scriptStatusMessage.value = "Status: OK (BOT_TOKEN = \"MY_BOT_TOKEN\" ভেরিফাইড)"
                    BotStateRepository.rawScriptContent.value = result
                    BotStateRepository.isCheckingScript.value = false
                    BotStateRepository.lastCheckTime.value = nowTime
                    BotStateRepository.addLog("স্ট্যাটাস", "Status: OK - সার্ভার প্রস্তুত", isSuccess = true)
                }
                ScriptStatus.FAILED -> {
                    BotStateRepository.scriptStatus.value = ScriptStatus.FAILED
                    BotStateRepository.scriptStatusMessage.value = "Status: FAILED ($result)"
                    BotStateRepository.rawScriptContent.value = ""
                    BotStateRepository.isCheckingScript.value = false
                    BotStateRepository.lastCheckTime.value = nowTime
                    BotStateRepository.addLog("স্ট্যাটাস", "Status: FAILED - $result", isError = true)
                }
                else -> {}
            }
        }
    }

    fun startBot() {
        val currentToken = BotStateRepository.getSavedBotToken().trim()
        if (currentToken.isBlank()) {
            BotStateRepository.addLog("সতর্কতা", "দয়া করে Bot Token ইনপুট করুন", isError = true)
            return
        }

        if (BotStateRepository.scriptStatus.value != ScriptStatus.OK) {
            BotStateRepository.addLog("সতর্কতা", "সার্ভার স্ট্যাটাস OK নয়। রিফ্রেশ বাটনে চাপ দিন", isError = true)
            return
        }

        if (BotStateRepository.botRunningState.value == BotRunningState.RUNNING ||
            BotStateRepository.botRunningState.value == BotRunningState.STARTING
        ) {
            return
        }

        // Save token to local permanent storage
        BotStateRepository.saveBotToken(currentToken)
        BotStateRepository.addLog("টোকেন", "টোকেন লোকাল স্টোরেজে সংরক্ষিত ও সংযুক্ত", isSuccess = true)

        // Start Foreground Service so bot runs 24/7 in background even when app is closed/swiped away
        BotForegroundServiceUtils.startService(
            getApplication(),
            currentToken,
            BotStateRepository.rawScriptContent.value
        )
    }

    fun stopBot() {
        BotForegroundServiceUtils.stopService(getApplication())
        BotStateRepository.botRunningState.value = BotRunningState.STOPPED
        BotStateRepository.botInfo.value = null
    }

    fun testTokenOnly() {
        val currentToken = BotStateRepository.getSavedBotToken().trim()
        if (currentToken.isBlank()) {
            BotStateRepository.addLog("টেস্ট", "দয়া করে Bot Token লিখুন", isError = true)
            return
        }

        viewModelScope.launch {
            isTestingTokenFlow.value = true
            BotStateRepository.addLog("টেস্ট", "টোকেন টেস্ট করা হচ্ছে...")

            val engine = TelegramEngine(
                botToken = currentToken,
                scriptContent = "",
                onLog = {},
                onStateChange = { _, _ -> }
            )
            val result = engine.testConnection()
            isTestingTokenFlow.value = false

            if (result.isSuccess) {
                val info = result.getOrNull()!!
                BotStateRepository.addLog("টেস্ট", "টোকেন সঠিক! বট: @${info.username} (${info.firstName})", isSuccess = true)
            } else {
                val err = result.exceptionOrNull()?.message ?: "ভেরিফিকেশন ব্যর্থ"
                BotStateRepository.addLog("টেস্ট", "টোকেন ভুল: $err", isError = true)
            }
        }
    }

    fun clearLogs() {
        BotStateRepository.clearLogs()
    }
}
