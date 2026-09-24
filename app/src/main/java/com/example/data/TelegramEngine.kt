package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TelegramEngine(
    private val botToken: String,
    private val scriptContent: String,
    private val onLog: (LogEntry) -> Unit,
    private val onStateChange: (BotRunningState, BotInfo?) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastUpdateId: Long = 0
    private var isRunning = false

    private fun log(
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
        onLog(entry)
    }

    suspend fun testConnection(): Result<BotInfo> = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(Exception("বট টোকেন খালি হতে পারে না"))
        }

        val url = "https://api.telegram.org/bot$cleanToken/getMe"
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("টেলিগ্রাম এপিআই ত্রুটি: ${response.code} - টোকেন সঠিক নয়"))
                }

                val json = JSONObject(bodyStr)
                if (json.optBoolean("ok", false)) {
                    val result = json.getJSONObject("result")
                    val info = BotInfo(
                        id = result.optLong("id"),
                        isBot = result.optBoolean("is_bot", true),
                        firstName = result.optString("first_name", "Telegram Bot"),
                        username = result.optString("username", ""),
                        canJoinGroups = result.optBoolean("can_join_groups", false),
                        canReadAllGroupMessages = result.optBoolean("can_read_all_group_messages", false),
                        supportsInlineQueries = result.optBoolean("supports_inline_queries", false)
                    )
                    Result.success(info)
                } else {
                    val desc = json.optString("description", "টোকেন অবৈধ")
                    Result.failure(Exception(desc))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("কানেকশন ফেইল্ড: ${e.localizedMessage}"))
        }
    }

    suspend fun startBotLoop() = withContext(Dispatchers.IO) {
        isRunning = true
        onStateChange(BotRunningState.STARTING, null)
        log("সিস্টেম", "টেলিগ্রাম সার্ভারে সংযোগ তৈরি হচ্ছে...", isSuccess = false)

        val testResult = testConnection()
        if (testResult.isFailure) {
            val err = testResult.exceptionOrNull()?.message ?: "টোকেন ভেরিফিকেশন ব্যর্থ"
            log("ত্রুটি", err, isError = true)
            onStateChange(BotRunningState.ERROR, null)
            isRunning = false
            return@withContext
        }

        val botInfo = testResult.getOrNull()!!
        log("সফল", "বট কানেক্টেড: @${botInfo.username} (${botInfo.firstName})", isSuccess = true)
        log("সিস্টেম", "লং-পোলিং (Long Polling) চালু হচ্ছে...", isSuccess = true)
        onStateChange(BotRunningState.RUNNING, botInfo)

        // Parse custom responses or commands if defined in the script
        val customGreeting = extractCustomGreeting(scriptContent)

        while (coroutineContext.isActive && isRunning) {
            try {
                pollUpdates(botInfo, customGreeting)
            } catch (e: CancellationException) {
                log("সিস্টেম", "বট বন্ধ করার অনুরোধ পাওয়া গেছে", isSuccess = false)
                break
            } catch (e: IOException) {
                if (isRunning) {
                    log("নেটওয়ার্ক", "কানেকশন রিট্রাই করা হচ্ছে... (${e.localizedMessage})")
                    delay(3000)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("ত্রুটি", "পোলিং সমস্যা: ${e.localizedMessage}", isError = true)
                    delay(4000)
                }
            }
        }

        isRunning = false
        onStateChange(BotRunningState.STOPPED, null)
        log("সিস্টেম", "বট সম্পূর্ণভাবে বন্ধ হয়েছে।")
    }

    private fun pollUpdates(botInfo: BotInfo, customGreeting: String) {
        val cleanToken = botToken.trim()
        val url = "https://api.telegram.org/bot$cleanToken/getUpdates?offset=$lastUpdateId&timeout=20"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return
            }

            val bodyStr = response.body?.string().orEmpty()
            if (bodyStr.isBlank()) return

            val json = JSONObject(bodyStr)
            if (!json.optBoolean("ok", false)) return

            val results = json.optJSONArray("result") ?: JSONArray()
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                val updateId = update.optLong("update_id")
                lastUpdateId = updateId + 1

                if (update.has("message")) {
                    handleMessage(update.getJSONObject("message"), botInfo, customGreeting)
                } else if (update.has("callback_query")) {
                    handleCallbackQuery(update.getJSONObject("callback_query"), botInfo)
                }
            }
        }
    }

    private fun handleMessage(msgObj: JSONObject, botInfo: BotInfo, customGreeting: String) {
        val from = msgObj.optJSONObject("from")
        val senderName = from?.optString("first_name", "User") ?: "User"
        val senderUsername = from?.optString("username", "") ?: ""
        val senderId = from?.optLong("id", 0) ?: 0
        val chat = msgObj.optJSONObject("chat")
        val chatId = chat?.optLong("id", 0) ?: return
        val text = msgObj.optString("text", "").trim()

        val senderDisplay = if (senderUsername.isNotBlank()) "@$senderUsername" else senderName
        log(
            "মেসেজ",
            "📩 $senderDisplay ($senderId): $text",
            isIncoming = true
        )

        // Handle auto-reply logic
        val replyText = when {
            text.startsWith("/start") -> {
                if (customGreeting.isNotBlank()) {
                    customGreeting
                } else {
                    "👋 হ্যালো $senderName!\nআমি @${botInfo.username} বট। অ্যাপ থেকে লাইভ রানিং আছি।\n\n📌 কমান্ডসমূহ:\n/start - শুরু করুন\n/help - সাহায্য\n/ping - পিং টেস্ট\n/status - বর্তমান স্ট্যাটাস"
                }
            }
            text.startsWith("/help") -> {
                "ℹ️ সাহায্য মেনু:\nবটটি সরাসরি অ্যান্ড্রয়েড অ্যাপ থেকে পরিচালিত হচ্ছে। যে কোনো মেসেজ পাঠালে বট রেসপন্স করবে।"
            }
            text.startsWith("/ping") -> {
                "🏓 Pong! বট সচল ও দ্রুত রেসপন্স করছে (সার্ভার টাইম: ${timeFormat.format(Date())})"
            }
            text.startsWith("/status") -> {
                "✅ বট স্ট্যাটাস: অনলাইন ও সক্রিয়\n🤖 নাম: ${botInfo.firstName}\n🆔 চ্যাট আইডি: $chatId"
            }
            text.isNotBlank() -> {
                "🤖 বট প্রাপ্ত হয়েছে: \"$text\"\nসময়: ${timeFormat.format(Date())}"
            }
            else -> {
                "🤖 আপনার মেসেজ গৃহীত হয়েছে।"
            }
        }

        sendMessage(chatId, replyText)
    }

    private fun handleCallbackQuery(cbObj: JSONObject, botInfo: BotInfo) {
        val from = cbObj.optJSONObject("from")
        val senderName = from?.optString("first_name", "User") ?: "User"
        val data = cbObj.optString("data", "")
        val message = cbObj.optJSONObject("message")
        val chatId = message?.optJSONObject("chat")?.optLong("id", 0) ?: return

        log("বাটন", "🔘 $senderName বাটনে চাপ দিয়েছেন: $data", isIncoming = true)
        sendMessage(chatId, "বাটন ক্লিক রেকর্ড করা হয়েছে: $data")
    }

    private fun sendMessage(chatId: Long, text: String) {
        try {
            val cleanToken = botToken.trim()
            val url = "https://api.telegram.org/bot$cleanToken/sendMessage"
            val formBody = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", text)
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    log(
                        "উত্তর",
                        "📤 [ID:$chatId]-কে পাঠানো হলো: ${text.take(60)}${if (text.length > 60) "..." else ""}",
                        isOutgoing = true
                    )
                } else {
                    log("ত্রুটি", "মেসেজ পাঠানো ব্যর্থ: ${response.code}", isError = true)
                }
            }
        } catch (e: Exception) {
            log("ত্রুটি", "মেসেজ পাঠাতে সমস্যা: ${e.localizedMessage}", isError = true)
        }
    }

    fun stop() {
        isRunning = false
    }

    private fun extractCustomGreeting(code: String): String {
        return try {
            val startRegex = Regex("""(?i)(?:start_message|welcome_text|GREETING)\s*=\s*["']([^"']+)["']""")
            val match = startRegex.find(code)
            match?.groups?.get(1)?.value ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
