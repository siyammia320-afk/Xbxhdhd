package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BotInfo
import com.example.data.BotRunningState
import com.example.data.LogEntry
import com.example.data.ScriptFetcher
import com.example.data.ScriptStatus
import com.example.data.TelegramEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val prefs = application.getSharedPreferences("bot_prefs", Context.MODE_PRIVATE)
    private val PREF_KEY_TOKEN = "saved_bot_token"

    private val _uiState = MutableStateFlow(BotUiState())
    val uiState: StateFlow<BotUiState> = _uiState.asStateFlow()

    private var botJob: Job? = null
    private var telegramEngine: TelegramEngine? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        // Load saved token
        val savedToken = prefs.getString(PREF_KEY_TOKEN, "") ?: ""
        _uiState.value = _uiState.value.copy(
            botToken = savedToken,
            isTokenSaved = savedToken.isNotBlank()
        )

        // Automatically check on app open
        checkRawLink()
    }

    fun onTokenChanged(newToken: String) {
        _uiState.value = _uiState.value.copy(botToken = newToken, isTokenSaved = false)
        prefs.edit().putString(PREF_KEY_TOKEN, newToken).apply()
        _uiState.value = _uiState.value.copy(isTokenSaved = newToken.isNotBlank())
    }

    fun checkRawLink() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCheckingScript = true,
                scriptStatus = ScriptStatus.CHECKING,
                scriptStatusMessage = "সার্ভার ও কনফিগারেশন চেক হচ্ছে..."
            )
            addLog("সিস্টেম", "সার্ভার সংযোগ যাচাই করা হচ্ছে...", isIncoming = false)

            val (status, result) = ScriptFetcher.fetchAndValidateScript()
            val nowTime = timeFormat.format(Date())

            when (status) {
                ScriptStatus.OK -> {
                    _uiState.value = _uiState.value.copy(
                        scriptStatus = ScriptStatus.OK,
                        scriptStatusMessage = "Status: OK (BOT_TOKEN = \"MY_BOT_TOKEN\" ভেরিফাইড)",
                        rawScriptContent = result,
                        isCheckingScript = false,
                        lastCheckTime = nowTime
                    )
                    addLog("স্ট্যাটাস", "Status: OK - সার্ভার প্রস্তুত", isSuccess = true)
                }
                ScriptStatus.FAILED -> {
                    _uiState.value = _uiState.value.copy(
                        scriptStatus = ScriptStatus.FAILED,
                        scriptStatusMessage = "Status: FAILED ($result)",
                        rawScriptContent = "",
                        isCheckingScript = false,
                        lastCheckTime = nowTime
                    )
                    addLog("স্ট্যাটাস", "Status: FAILED - $result", isError = true)
                }
                else -> {}
            }
        }
    }

    fun startBot() {
        val currentToken = _uiState.value.botToken.trim()
        if (currentToken.isBlank()) {
            addLog("সতর্কতা", "দয়া করে Bot Token ইনপুট করুন", isError = true)
            return
        }

        if (_uiState.value.scriptStatus != ScriptStatus.OK) {
            addLog("সতর্কতা", "সার্ভার স্ট্যাটাস OK নয়। রিফ্রেশ বাটনে চাপ দিন", isError = true)
            return
        }

        if (_uiState.value.botRunningState == BotRunningState.RUNNING ||
            _uiState.value.botRunningState == BotRunningState.STARTING
        ) {
            return
        }

        // Save token to prefs
        prefs.edit().putString(PREF_KEY_TOKEN, currentToken).apply()

        // Inject token into script
        val finalScript = ScriptFetcher.injectBotToken(_uiState.value.rawScriptContent, currentToken)
        addLog("টোকেন", "টোকেন সংযুক্ত করা হয়েছে", isSuccess = true)

        botJob?.cancel()
        botJob = viewModelScope.launch {
            val engine = TelegramEngine(
                botToken = currentToken,
                scriptContent = finalScript,
                onLog = { entry ->
                    addLogEntry(entry)
                    if (entry.isIncoming) {
                        _uiState.value = _uiState.value.copy(
                            totalProcessedMessages = _uiState.value.totalProcessedMessages + 1
                        )
                    }
                },
                onStateChange = { state, info ->
                    _uiState.value = _uiState.value.copy(
                        botRunningState = state,
                        botInfo = info
                    )
                }
            )
            telegramEngine = engine
            engine.startBotLoop()
        }
    }

    fun stopBot() {
        telegramEngine?.stop()
        botJob?.cancel()
        _uiState.value = _uiState.value.copy(
            botRunningState = BotRunningState.STOPPED,
            botInfo = null
        )
        addLog("সিস্টেম", "বট বন্ধ করা হয়েছে")
    }

    fun testTokenOnly() {
        val currentToken = _uiState.value.botToken.trim()
        if (currentToken.isBlank()) {
            addLog("টেস্ট", "দয়া করে Bot Token লিখুন", isError = true)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isTestingToken = true)
            addLog("টেস্ট", "টোকেন টেস্ট করা হচ্ছে...")

            val engine = TelegramEngine(
                botToken = currentToken,
                scriptContent = "",
                onLog = {},
                onStateChange = { _, _ -> }
            )
            val result = engine.testConnection()
            _uiState.value = _uiState.value.copy(isTestingToken = false)

            if (result.isSuccess) {
                val info = result.getOrNull()!!
                addLog("টেস্ট", "টোকেন সঠিক! বট: @${info.username} (${info.firstName})", isSuccess = true)
            } else {
                val err = result.exceptionOrNull()?.message ?: "ভেরিফিকেশন ব্যর্থ"
                addLog("টেস্ট", "টোকেন ভুল: $err", isError = true)
            }
        }
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    private fun addLog(
        tag: String,
        message: String,
        isError: Boolean = false,
        isSuccess: Boolean = false,
        isIncoming: Boolean = false,
        isOutgoing: Boolean = false
    ) {
        val entry = LogEntry(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message,
            isError = isError,
            isSuccess = isSuccess,
            isIncoming = isIncoming,
            isOutgoing = isOutgoing
        )
        addLogEntry(entry)
    }

    private fun addLogEntry(entry: LogEntry) {
        val currentLogs = _uiState.value.logs.toMutableList()
        currentLogs.add(entry)
        if (currentLogs.size > 200) {
            currentLogs.removeAt(0)
        }
        _uiState.value = _uiState.value.copy(logs = currentLogs)
    }

    override fun onCleared() {
        super.onCleared()
        telegramEngine?.stop()
        botJob?.cancel()
    }
}
