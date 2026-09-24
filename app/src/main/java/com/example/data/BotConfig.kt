package com.example.data

data class BotInfo(
    val id: Long = 0,
    val isBot: Boolean = true,
    val firstName: String = "",
    val username: String = "",
    val canJoinGroups: Boolean = false,
    val canReadAllGroupMessages: Boolean = false,
    val supportsInlineQueries: Boolean = false
)

enum class ScriptStatus {
    CHECKING,
    OK,
    FAILED
}

enum class BotRunningState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR
}

data class LogEntry(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val timestamp: String,
    val tag: String,
    val message: String,
    val isError: Boolean = false,
    val isSuccess: Boolean = false,
    val isIncoming: Boolean = false,
    val isOutgoing: Boolean = false
)
