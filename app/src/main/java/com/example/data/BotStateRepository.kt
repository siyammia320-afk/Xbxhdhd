package com.example.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BotStateRepository {

    private const val PREFS_NAME = "telegram_bot_local_storage"
    private const val KEY_BOT_TOKEN = "saved_bot_token_permanent"
    private const val KEY_LAST_STATUS = "saved_last_status"

    private lateinit var sharedPreferences: SharedPreferences
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val botRunningState = MutableStateFlow(BotRunningState.STOPPED)
    val botInfo = MutableStateFlow<BotInfo?>(null)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val totalProcessedMessages = MutableStateFlow(0)
    val scriptStatus = MutableStateFlow(ScriptStatus.CHECKING)
    val scriptStatusMessage = MutableStateFlow("সার্ভার ও কনফিগারেশন চেক হচ্ছে...")
    val rawScriptContent = MutableStateFlow("")
    val lastCheckTime = MutableStateFlow("")
    val isCheckingScript = MutableStateFlow(false)
    val botToken = MutableStateFlow("")

    fun init(context: Context) {
        if (!::sharedPreferences.isInitialized) {
            sharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedToken = sharedPreferences.getString(KEY_BOT_TOKEN, "") ?: ""
            botToken.value = savedToken
        }
    }

    fun saveBotToken(token: String) {
        val cleanToken = token.trim()
        botToken.value = cleanToken
        if (::sharedPreferences.isInitialized) {
            // commit() ensures immediate synchronous write to disk so it will never be lost
            sharedPreferences.edit().putString(KEY_BOT_TOKEN, cleanToken).commit()
        }
    }

    fun getSavedBotToken(): String {
        return if (::sharedPreferences.isInitialized) {
            sharedPreferences.getString(KEY_BOT_TOKEN, "") ?: ""
        } else {
            botToken.value
        }
    }

    fun addLog(
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

    fun addLogEntry(entry: LogEntry) {
        val currentList = logs.value.toMutableList()
        currentList.add(entry)
        if (currentList.size > 200) {
            currentList.removeAt(0)
        }
        logs.value = currentList
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
