package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.random.Random

data class UserSession(
    var mode: String = "",       // "auto" or "manual"
    var step: String = "",       // "password", "email", "otp_wait", "otp_input"
    var email: String = "",
    var password: String = "",
    var tempToken: String = "",
    var otp: String = "",
    var uid: String = "",
    var cookie: String = "",
    var csi: String = "",
    var waterfallId: String = "",
    var confirmLink: String = "",
    var actorId: String = "",
    var fbDtsg: String = "",
    var lsd: String = "",
    var lastMessageId: Long = 0
)

class TelegramEngine(
    private val botToken: String,
    private val scriptContent: String,
    private val onLog: (LogEntry) -> Unit,
    private val onStateChange: (BotRunningState, BotInfo?) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userSessions = ConcurrentHashMap<Long, UserSession>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var lastUpdateId: Long = 0
    private var isRunning = false

    private val TARGET_CREATE_URL = "https://auth.meta.com/login/device-based/register-save-credentials/"
    private val TARGET_CONFIRM_URL = "https://auth.meta.com/api/graphql/"
    private val TEMPMAIL_CREATE_URL = "https://instanttempemail.com/api/create"
    private val TEMPMAIL_INBOX_URL = "https://instanttempemail.com/api/inbox/"

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
        log("সিস্টেম", "টেলিগ্রাম সার্ভারে সংযোগ তৈরি হচ্ছে...")

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
        log("স্ট্যাটাস", "বট লাইভ রানিং আছে। প্রস্তুত।", isSuccess = true)
        onStateChange(BotRunningState.RUNNING, botInfo)

        while (coroutineContext.isActive && isRunning) {
            try {
                pollUpdates(botInfo)
            } catch (e: CancellationException) {
                break
            } catch (e: IOException) {
                if (isRunning) {
                    delay(3000)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    log("ত্রুটি", "পোলিং এরর: ${e.localizedMessage}", isError = true)
                    delay(4000)
                }
            }
        }

        isRunning = false
        onStateChange(BotRunningState.STOPPED, null)
        log("সিস্টেম", "বট সম্পূর্ণভাবে বন্ধ হয়েছে।")
    }

    private suspend fun pollUpdates(botInfo: BotInfo) = withContext(Dispatchers.IO) {
        val cleanToken = botToken.trim()
        val url = "https://api.telegram.org/bot$cleanToken/getUpdates?offset=$lastUpdateId&timeout=20"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext

            val bodyStr = response.body?.string().orEmpty()
            if (bodyStr.isBlank()) return@withContext

            val json = JSONObject(bodyStr)
            if (!json.optBoolean("ok", false)) return@withContext

            val results = json.optJSONArray("result") ?: JSONArray()
            for (i in 0 until results.length()) {
                val update = results.getJSONObject(i)
                val updateId = update.optLong("update_id")
                lastUpdateId = updateId + 1

                if (update.has("message")) {
                    handleMessage(update.getJSONObject("message"), botInfo)
                } else if (update.has("callback_query")) {
                    handleCallbackQuery(update.getJSONObject("callback_query"), botInfo)
                }
            }
        }
    }

    // ================= KEYBOARDS =================
    private fun getMainMenuKeyboard(): String {
        return """
        {
            "inline_keyboard": [
                [{"text": "⚡ Auto Create (Temp Mail)", "callback_data": "auto_create"}],
                [{"text": "✉ Manual Create (Custom Email)", "callback_data": "manual_create"}]
            ]
        }
        """.trimIndent()
    }

    private fun getCancelKeyboard(): String {
        return """
        {
            "inline_keyboard": [
                [{"text": "❌ Cancel", "callback_data": "cancel"}]
            ]
        }
        """.trimIndent()
    }

    private suspend fun handleMessage(msgObj: JSONObject, botInfo: BotInfo) {
        val from = msgObj.optJSONObject("from")
        val senderName = from?.optString("first_name", "User") ?: "User"
        val senderUsername = from?.optString("username", "") ?: ""
        val userId = from?.optLong("id", 0) ?: return
        val chat = msgObj.optJSONObject("chat")
        val chatId = chat?.optLong("id", 0) ?: return
        val text = msgObj.optString("text", "").trim()

        val senderDisplay = if (senderUsername.isNotBlank()) "@$senderUsername" else senderName
        log("মেসেজ", "📩 $senderDisplay: $text", isIncoming = true)

        if (text == "/start") {
            userSessions.remove(userId)
            val startText = "⚡ *META ACCOUNT CREATOR BOT*\n━━━━━━━━━━━━━━━━━━━━━━━━\nনিচের অপশন থেকে বেছে নিন:\n━━━━━━━━━━━━━━━━━━━━━━━━"
            sendMessage(chatId, startText, parseMode = "Markdown", replyMarkup = getMainMenuKeyboard())
            return
        }

        if (text == "/cancel") {
            userSessions.remove(userId)
            sendMessage(chatId, "❌ Canceled.", replyMarkup = getMainMenuKeyboard())
            return
        }

        val session = userSessions[userId]
        if (session == null) {
            sendMessage(chatId, "Main Menu:", replyMarkup = getMainMenuKeyboard())
            return
        }

        val mode = session.mode
        val step = session.step

        // ---------- AUTO CREATE ----------
        if (mode == "auto" && step == "password") {
            if (text.length < 6) {
                sendMessage(chatId, "❌ পাসওয়ার্ড কমপক্ষে ৬ অক্ষর দিন।")
                return
            }
            session.password = text
            val msgId = sendMessage(chatId, "➜ Temp Mail তৈরি করা হচ্ছে...")
            session.lastMessageId = msgId

            val temp = createTempMail()
            if (temp == null || temp.address.isBlank()) {
                if (msgId > 0) editMessage(chatId, msgId, "❌ Temp Mail fail.")
                else sendMessage(chatId, "❌ Temp Mail fail.")
                userSessions.remove(userId)
                return
            }

            session.email = temp.address
            session.tempToken = temp.token

            if (msgId > 0) {
                editMessage(chatId, msgId, "✓ Temp Mail: `${temp.address}`\n\n➜ Meta Account তৈরি হচ্ছে...", parseMode = "Markdown")
            }

            val result = createMetaAccount(temp.address, session.password)
            if (!result.success) {
                val failMsg = "❌ Create failed: ${result.message}"
                if (msgId > 0) editMessage(chatId, msgId, failMsg)
                else sendMessage(chatId, failMsg)
                userSessions.remove(userId)
                return
            }

            session.uid = result.uid
            session.cookie = result.cookie
            session.confirmLink = result.confirmLink
            session.csi = result.csi
            session.waterfallId = result.waterfallId
            session.actorId = result.actorId
            session.fbDtsg = result.fbDtsg
            session.lsd = result.lsd
            session.step = "otp_wait"

            val createdText = "✅ *ACCOUNT CREATED*\n━━━━━━━━━━━━━━━━━━━━━━━━\n📧 ${temp.address}\n🆔 UID: `${result.uid}`\n🔑 Password: `${session.password}`\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n➜ OTP এর জন্য অপেক্ষা করা হচ্ছে..."
            if (msgId > 0) editMessage(chatId, msgId, createdText, parseMode = "Markdown")
            else sendMessage(chatId, createdText, parseMode = "Markdown")

            // Auto OTP Poll
            var otpFound = false
            for (attempt in 0 until 30) {
                if (!isRunning) break
                val emails = fetchInbox(temp.token)
                for (em in emails) {
                    val body = em.bodyText + " " + em.bodyHtml
                    if (body.contains("Confirm that you're human", ignoreCase = true)) {
                        if (msgId > 0) editMessage(chatId, msgId, "⚠️ Human Checkpoint! Meta verification চাইছে।")
                        userSessions.remove(userId)
                        return
                    }

                    val matcher = Pattern.compile("Confirmation code\\s*[:\\s]*(\\d{6})", Pattern.CASE_INSENSITIVE).matcher(body)
                    val otp = if (matcher.find()) {
                        matcher.group(1)
                    } else {
                        val simpleMatch = Pattern.compile("\\b(\\d{6})\\b").matcher(body)
                        if (simpleMatch.find()) simpleMatch.group(1) else null
                    }

                    if (!otp.isNullOrBlank()) {
                        session.otp = otp
                        otpFound = true
                        if (msgId > 0) {
                            editMessage(chatId, msgId, "✓ OTP পেয়েছি: `$otp`\n➜ Confirm করা হচ্ছে...", parseMode = "Markdown")
                        }
                        delay(1000)
                        doConfirm(chatId, userId, session)
                        return
                    }
                }
                delay(3000)
            }

            if (!otpFound) {
                if (msgId > 0) editMessage(chatId, msgId, "❌ OTP Timeout।")
                userSessions.remove(userId)
            }
        }
        // ---------- MANUAL CREATE ----------
        else if (mode == "manual" && step == "email") {
            if (!text.contains("@") || !text.contains(".")) {
                sendMessage(chatId, "❌ সঠিক email দিন।")
                return
            }
            session.email = text
            session.step = "password"
            sendMessage(
                chatId,
                "✓ Email: `$text`\n\n➤ এখন পাসওয়ার্ড পাঠান (কমপক্ষে ৬ অক্ষর):",
                parseMode = "Markdown",
                replyMarkup = getCancelKeyboard()
            )
        } else if (mode == "manual" && step == "password") {
            if (text.length < 6) {
                sendMessage(chatId, "❌ পাসওয়ার্ড কমপক্ষে ৬ অক্ষর দিন।")
                return
            }
            session.password = text
            session.step = "creating"
            val msgId = sendMessage(chatId, "➜ Meta Account তৈরি হচ্ছে...")

            val result = createMetaAccount(session.email, session.password)
            if (!result.success) {
                val failMsg = "❌ Failed: ${result.message}"
                if (msgId > 0) editMessage(chatId, msgId, failMsg)
                else sendMessage(chatId, failMsg)
                userSessions.remove(userId)
                return
            }

            session.uid = result.uid
            session.cookie = result.cookie
            session.confirmLink = result.confirmLink
            session.csi = result.csi
            session.waterfallId = result.waterfallId
            session.actorId = result.actorId
            session.fbDtsg = result.fbDtsg
            session.lsd = result.lsd
            session.step = "otp_input"

            val promptText = "✅ *ACCOUNT CREATED*\n━━━━━━━━━━━━━━━━━━━━━━━━\n📧 ${session.email}\n🆔 UID: `${result.uid}`\n🔑 Password: `${session.password}`\n━━━━━━━━━━━━━━━━━━━━━━━━\n\n➤ এখন আপনার ইমেইলে আসা ৬-ডিজিট OTP পাঠান:"
            if (msgId > 0) editMessage(chatId, msgId, promptText, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
            else sendMessage(chatId, promptText, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
        } else if (mode == "manual" && step == "otp_input") {
            val cleanOtp = text.filter { it.isDigit() }
            if (cleanOtp.length < 4) {
                sendMessage(chatId, "❌ সঠিক OTP দিন।")
                return
            }
            session.otp = cleanOtp
            val msgId = sendMessage(chatId, "➜ Confirm করা হচ্ছে...")
            delay(1000)
            doConfirm(chatId, userId, session)
        }
    }

    private suspend fun handleCallbackQuery(cbObj: JSONObject, botInfo: BotInfo) {
        val queryId = cbObj.optString("id", "")
        val from = cbObj.optJSONObject("from")
        val userId = from?.optLong("id", 0) ?: return
        val message = cbObj.optJSONObject("message")
        val chatId = message?.optJSONObject("chat")?.optLong("id", 0) ?: return
        val msgId = message?.optLong("message_id", 0) ?: 0
        val data = cbObj.optString("data", "")

        answerCallbackQuery(queryId)
        log("অ্যাকশন", "🔘 বাটন সিলেক্ট: $data")

        if (data == "cancel") {
            userSessions.remove(userId)
            if (msgId > 0) editMessage(chatId, msgId, "❌ Canceled.", replyMarkup = getMainMenuKeyboard())
            else sendMessage(chatId, "❌ Canceled.", replyMarkup = getMainMenuKeyboard())
            return
        }

        if (data == "auto_create") {
            userSessions[userId] = UserSession(mode = "auto", step = "password")
            val text = "⚡ *AUTO CREATE*\n━━━━━━━━━━━━━━━━━━━━━━━━\nপ্রতিটা অ্যাকাউন্টের জন্য আলাদা পাসওয়ার্ড দিতে হবে।\n\n➤ আপনার পাসওয়ার্ড পাঠান (কমপক্ষে ৬ অক্ষর):"
            if (msgId > 0) editMessage(chatId, msgId, text, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
            else sendMessage(chatId, text, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
            return
        }

        if (data == "manual_create") {
            userSessions[userId] = UserSession(mode = "manual", step = "email")
            val text = "✉ *MANUAL CREATE*\n━━━━━━━━━━━━━━━━━━━━━━━━\n➤ আপনার ইমেইল পাঠান:"
            if (msgId > 0) editMessage(chatId, msgId, text, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
            else sendMessage(chatId, text, parseMode = "Markdown", replyMarkup = getCancelKeyboard())
            return
        }
    }

    private suspend fun doConfirm(chatId: Long, userId: Long, session: UserSession) {
        val confirm = confirmMetaOtp(session, session.otp)
        if (confirm.confirmed) {
            val cookieSnippet = if (session.cookie.length > 80) session.cookie.take(80) + "..." else session.cookie
            val text = "✅ *ACCOUNT CONFIRMED!*\n━━━━━━━━━━━━━━━━━━━━━━━━\n📧 Email: `${session.email}`\n🆔 UID: `${confirm.uid}`\n🔑 Password: `${session.password}`\n🍪 Cookie: `$cookieSnippet`\n━━━━━━━━━━━━━━━━━━━━━━━━"
            sendMessage(chatId, text, parseMode = "Markdown")
            log("সফল", "অ্যাকাউন্ট তৈরি ও কনফার্ম সফল: ${session.email} (UID: ${confirm.uid})", isSuccess = true)
        } else {
            sendMessage(chatId, "❌ Confirmation failed.")
            log("ব্যর্থ", "কনফার্মেশন ব্যর্থ হয়েছে: ${session.email}", isError = true)
        }

        userSessions.remove(userId)
        sendMessage(chatId, "Main Menu:", replyMarkup = getMainMenuKeyboard())
    }

    // ================= META ACCOUNT CREATION & CONFIRMATION (1:1 PYTHON LOGIC) =================
    data class MetaCreateResult(
        val success: Boolean,
        val email: String = "",
        val uid: String = "",
        val message: String = "",
        val cookie: String = "",
        val csi: String = "",
        val waterfallId: String = "",
        val confirmLink: String = "",
        val actorId: String = "",
        val fbDtsg: String = "",
        val lsd: String = ""
    )

    data class MetaConfirmResult(
        val confirmed: Boolean,
        val uid: String
    )

    data class TempMailData(val address: String, val token: String)
    data class TempEmailItem(val bodyText: String, val bodyHtml: String)

    private fun generateRandomToken(length: Int = 24): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun parseMetaResponse(text: String): JSONObject? {
        var clean = text.trim()
        if (clean.startsWith("for (;;);")) {
            clean = clean.substring("for (;;);".length)
        }
        return try {
            JSONObject(clean)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTokensAndUid(html: String): Triple<String, String, String> {
        var fbDtsg = ""
        var lsd = ""
        var actorId = ""

        val dtsgPatterns = listOf(
            "\\[\"DTSGInitialData\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}",
            "name=\"fb_dtsg\"\\s+value=\"([^\"]+)\"",
            "\"token\":\"(NAf[^\"]+)\""
        )
        for (p in dtsgPatterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) {
                fbDtsg = m.group(1) ?: ""
                break
            }
        }

        val lsdPatterns = listOf(
            "\\[\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}",
            "name=\"lsd\"\\s+value=\"([^\"]+)\"",
            "\"token\":\"([0-9a-zA-Z_\\-]{20,})\""
        )
        for (p in lsdPatterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) {
                lsd = m.group(1) ?: ""
                break
            }
        }

        val uidPatterns = listOf(
            "\"ACCOUNT_ID\":\"(\\d+)\"",
            "\"USER_ID\":\"(\\d+)\"",
            "\"actor_id\":\"(\\d+)\""
        )
        for (p in uidPatterns) {
            val m = Pattern.compile(p).matcher(html)
            if (m.find()) {
                val found = m.group(1) ?: ""
                if (found != "0") {
                    actorId = found
                    break
                }
            }
        }

        return Triple(fbDtsg, lsd, actorId)
    }

    private fun classifyCreateResponse(statusCode: Int, data: JSONObject?, rawText: String): Pair<Boolean, String> {
        if (data == null) {
            if (rawText.contains("uid")) {
                val m = Pattern.compile("\"uid\":\\s*\"?(\\d+)\"?").matcher(rawText)
                if (m.find()) {
                    return Pair(true, m.group(1) ?: "")
                }
            }
            return Pair(false, "Unable to parse response")
        }

        val payload = data.optJSONObject("payload")
        if (payload != null && payload.has("uid")) {
            return Pair(true, payload.optString("uid"))
        }

        if (data.has("error")) {
            val desc = data.optString("errorDescription")
                .ifEmpty { data.optString("errorSummary") }
                .ifEmpty { "Unknown error" }
            return Pair(false, desc)
        }

        return Pair(false, "Unknown response")
    }

    private fun calculateJazoest(token: String): String {
        if (token.isEmpty()) return "25584"
        val sum = token.toCharArray().sumOf { it.code }
        return "2$sum"
    }

    private suspend fun createTempMail(): TempMailData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(TEMPMAIL_CREATE_URL)
                .post("".toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; itel S665L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36")
                .header("Origin", "https://instanttempemail.com")
                .header("Referer", "https://instanttempemail.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val address = json.optString("address", "")
                    val token = json.optString("token", "")
                    if (address.isNotBlank()) {
                        return@withContext TempMailData(address, token)
                    }
                }
            }
        } catch (e: Exception) {
            log("এরর", "Temp Mail ত্রুটি: ${e.localizedMessage}", isError = true)
        }
        null
    }

    private suspend fun fetchInbox(token: String): List<TempEmailItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<TempEmailItem>()
        if (token.isBlank()) return@withContext list
        try {
            val request = Request.Builder()
                .url("$TEMPMAIL_INBOX_URL$token")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; itel S665L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36")
                .header("Origin", "https://instanttempemail.com")
                .header("Referer", "https://instanttempemail.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val jsonArr = json.optJSONArray("emails") ?: JSONArray()
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.getJSONObject(i)
                        list.add(
                            TempEmailItem(
                                bodyText = obj.optString("body_text", ""),
                                bodyHtml = obj.optString("body_html", "")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore polling transient errors
        }
        list
    }

    private suspend fun createMetaAccount(email: String, pass: String): MetaCreateResult = withContext(Dispatchers.IO) {
        try {
            val savedCsi = "Scd0BS3l-yOix38o6lNKa_kT"
            val savedWf = "701777af-c668-4a8d-976a-0c0f619d807a"

            val formBuilder = FormBody.Builder()
                .add("consent_version", "")
                .add("contact_point_type", "EMAIL_ADDRESS")
                .add("csi", savedCsi)
                .add("date_of_birth", "1993-09-11")
                .add("device_id", "")
                .add("fb_encrypted_access_token", "")
                .add("fb_oidc_access_token", "")
                .add("first_name", "Ajs")
                .add("google_id_token", "")
                .add("has_youth_consent", "false")
                .add("ig_encrypted_access_token", "")
                .add("ig_encrypted_auth_header", "")
                .add("ig_oidc_access_token", "")
                .add("last_name", "Sjs")
                .add("opt_into_marketing", "true")
                .add("password", pass)
                .add("reg_integrity", "Q8W2BTuKa24cQO_B6qvNeGtvmIjuAiCCCvXaCbgkwfWbqt-rPjXJArjnIu5K2myj2GHMJPPSr6BbjXBUFU17JuiZ3IvWTGB_fpfbXwr1sq6qX5lwBCHho2TWDE4ACpgpKGop91SIXofE0KTu2MBkdW1Ss0D6TG7isv6lz2N1CLlYDcRuoiMmSnzt_3_tNldGlheeYm1KVKQyQckdk6G2PoiceW2vxKWbivZ6HJPdq-QsNs4JB7yqEnYY2B3u6mfepC066IZYhv9ZgWpXIuYUsgim6pBbL6NyF84-UsOy8kYIOZlCHc_2PFK4SRPhdx0RMJipGYiIeb5y-Nwj_VHS1Tc2es-jc6aZ7hlBsBokGAeo-cnYEERpIKXz_OnmFTc48WHp2nMcJxww|kregenc")
                .add("should_save_credentials", "true")
                .add("waterfall_id", savedWf)
                .add("caa_event_flow", "ntf")
                .add("entry_point", "login_home")
                .add("event_client_time", "${System.currentTimeMillis() / 1000}.654")
                .add("is_kadabra_zero", "false")
                .add("regulation_jurisdiction", "[\"BD\"]")
                .add("qpl_join_id", "ff52ee3c05b3ec955")
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "1q")
                .add("__rev", "1047214234")
                .add("lsd", "AdRLdRXnKs4_RAGnmEr-k2XaQu0")
                .add("jazoest", "22293")
                .add("__spin_r", "1047214234")
                .add("__spin_b", "trunk")
                .add("__spin_t", "${System.currentTimeMillis() / 1000}")
                .add("__jssesw", "1")
                .add("contact_point", email)
                .add("redirect_uri", "https://auth.meta.com/recover/success/?redirect_uri=https%3A%2F%2Fauth.meta.com%2Foidc%3Fapp_id%3D1522763855472543")

            val request = Request.Builder()
                .url(TARGET_CREATE_URL)
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; itel S665L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Android WebView\";v=\"152\"")
                .header("sec-ch-ua-mobile", "?1")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("x-asbd-id", "359341")
                .header("x-fb-lsd", "AdRLdRXnKs4_RAGnmEr-k2XaQu0")
                .header("origin", "https://auth.meta.com")
                .header("x-requested-with", "mark.via.gp")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-dest", "empty")
                .header("referer", "https://auth.meta.com/")
                .header("accept-language", "en-US,en;q=0.9")
                .header("priority", "u=1, i")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = parseMetaResponse(body)
                val (success, reason) = classifyCreateResponse(response.code, parsed, body)

                val fullCookies = mutableMapOf(
                    "datr" to generateRandomToken(24),
                    "ps_l" to "1",
                    "ps_n" to "1",
                    "locale" to "en_GB"
                )
                for (cookie in response.headers("Set-Cookie")) {
                    val pair = cookie.split(";")[0].split("=")
                    if (pair.size >= 2) {
                        fullCookies[pair[0].trim()] = pair[1].trim()
                    }
                }
                val cookieStr = fullCookies.map { "${it.key}=${it.value}" }.joinToString("; ")

                var extractedUid = ""
                if (parsed != null && parsed.optJSONObject("payload") != null) {
                    extractedUid = parsed.getJSONObject("payload").optString("uid", "")
                } else if (success && reason.all { it.isDigit() }) {
                    extractedUid = reason
                }

                if (!success || extractedUid.isBlank()) {
                    return@withContext MetaCreateResult(success = false, message = reason)
                }

                val confirmLink = "https://auth.meta.com/register/confirm/?redirect_uri=https%3A%2F%2Fauth.meta.com%2Foidc%2F%3Fapp_id%3D1522763855472543&waterfall_id=$savedWf&csi=$savedCsi&event_flow=login_manual"

                val checkRequest = Request.Builder()
                    .url(confirmLink)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; itel S665L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36")
                    .header("Accept-Encoding", "gzip, deflate")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("sec-ch-ua-platform", "\"Android\"")
                    .header("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Android WebView\";v=\"152\"")
                    .header("x-fb-friendly-name", "FRLConfirmEmailMutation")
                    .header("sec-ch-ua-mobile", "?1")
                    .header("x-asbd-id", "359341")
                    .header("origin", "https://auth.meta.com")
                    .header("x-requested-with", "mark.via.gp")
                    .header("sec-fetch-site", "same-origin")
                    .header("sec-fetch-mode", "cors")
                    .header("sec-fetch-dest", "empty")
                    .header("accept-language", "en-US,en;q=0.9")
                    .header("priority", "u=1, i")
                    .header("Cookie", cookieStr)
                    .header("referer", confirmLink)
                    .build()

                var liveDtsg = ""
                var liveLsd = ""
                var pageUid = ""

                try {
                    client.newCall(checkRequest).execute().use { confResp ->
                        val confText = confResp.body?.string().orEmpty()
                        val extracted = extractTokensAndUid(confText)
                        liveDtsg = extracted.first
                        liveLsd = extracted.second
                        pageUid = extracted.third
                    }
                } catch (e: Exception) {
                    // Ignore transient errors
                }

                val finalUid = extractedUid.ifEmpty { pageUid }

                MetaCreateResult(
                    success = true,
                    email = email,
                    uid = finalUid,
                    cookie = cookieStr,
                    csi = savedCsi,
                    waterfallId = savedWf,
                    confirmLink = confirmLink,
                    actorId = finalUid,
                    fbDtsg = liveDtsg,
                    lsd = liveLsd
                )
            }
        } catch (e: Exception) {
            MetaCreateResult(success = false, message = e.localizedMessage ?: "Unknown error")
        }
    }

    private suspend fun confirmMetaOtp(session: UserSession, otpCode: String): MetaConfirmResult = withContext(Dispatchers.IO) {
        try {
            val actorId = session.uid
            val cookie = session.cookie
            val savedCsi = session.csi.ifEmpty { "Scd0BS3l-yOix38o6lNKa_kT" }
            val savedWf = session.waterfallId.ifEmpty { "701777af-c668-4a8d-976a-0c0f619d807a" }
            val fbDtsg = session.fbDtsg.ifEmpty { "NAfw3-iVgzAwb3wze6-QRU-d6X36d-knUVwny-8I9gCaoBHl9mph0_A:16:1789089771" }
            val lsd = session.lsd.ifEmpty { "mVvZ2A2krrrCYh31NtUS0j" }
            val jazoest = calculateJazoest(fbDtsg)

            val randomHex = (1..16).map { "0123456789abcdef".random() }.joinToString("")
            val qplJoinId = "f$randomHex"

            val vPayload = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("confirmation_code", JSONObject().put("sensitive_string_value", otpCode))
                    put("confirmation_code_type", "OTP_CODE")
                    put("event_flow", "login_manual")
                    put("rl_client_session_id", savedCsi)
                    put("waterfall_id", savedWf)
                    put("source_app_id", "1522763855472543")
                    put("qpl_join_id", qplJoinId)
                    put("actor_id", actorId)
                    put("client_mutation_id", "1")
                })
            }

            val formBuilder = FormBody.Builder()
                .add("av", actorId)
                .add("__user", "0")
                .add("__a", "1")
                .add("__req", "g")
                .add("__hs", "20707.HYP:frl_comet_auth_pkg.2.1...0")
                .add("dpr", "2")
                .add("__ccg", "MODERATE")
                .add("__rev", "1047236770")
                .add("fb_dtsg", fbDtsg)
                .add("jazoest", jazoest)
                .add("lsd", lsd)
                .add("variables", vPayload.toString())
                .add("doc_id", "9851798224911796")

            val reqBuilder = Request.Builder()
                .url(TARGET_CONFIRM_URL)
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; itel S665L Build/SP1A.210812.016) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.7977.87 Mobile Safari/537.36")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("sec-ch-ua-platform", "\"Android\"")
                .header("sec-ch-ua", "\"Chromium\";v=\"152\", \"Not?A_Brand\";v=\"24\", \"Android WebView\";v=\"152\"")
                .header("x-fb-friendly-name", "FRLConfirmEmailMutation")
                .header("sec-ch-ua-mobile", "?1")
                .header("x-asbd-id", "359341")
                .header("origin", "https://auth.meta.com")
                .header("x-requested-with", "mark.via.gp")
                .header("sec-fetch-site", "same-origin")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-dest", "empty")
                .header("accept-language", "en-US,en;q=0.9")
                .header("priority", "u=1, i")
                .header("Cookie", cookie)
                .header("x-fb-lsd", lsd)
                .header("referer", session.confirmLink.ifEmpty { "https://auth.meta.com/register/confirm/" })

            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val parsed = parseMetaResponse(body)
                val confirmInfo = parsed?.optJSONObject("data")?.optJSONObject("confirm_email")
                val isConfirmed = confirmInfo?.optBoolean("isConfirmed", false) ?: (body.contains("\"isConfirmed\":true") || body.contains("true"))
                val accountId = confirmInfo?.optString("accountId", actorId) ?: actorId

                MetaConfirmResult(
                    confirmed = isConfirmed,
                    uid = if (accountId.isNotBlank()) accountId else actorId
                )
            }
        } catch (e: Exception) {
            MetaConfirmResult(confirmed = false, uid = session.uid)
        }
    }

    // ================= TELEGRAM API HELPERS =================
    private fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        replyMarkup: String? = null
    ): Long {
        try {
            val cleanToken = botToken.trim()
            val url = "https://api.telegram.org/bot$cleanToken/sendMessage"
            val formBuilder = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("text", text)

            if (!parseMode.isNullOrBlank()) {
                formBuilder.add("parse_mode", parseMode)
            }
            if (!replyMarkup.isNullOrBlank()) {
                formBuilder.add("reply_markup", replyMarkup)
            }

            val request = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val msgId = json.optJSONObject("result")?.optLong("message_id", 0) ?: 0
                    log(
                        "উত্তর",
                        "📤 [ID:$chatId]: ${text.take(45)}${if (text.length > 45) "..." else ""}",
                        isOutgoing = true
                    )
                    return msgId
                }
            }
        } catch (e: Exception) {
            log("ত্রুটি", "মেসেজ ব্যর্থ: ${e.localizedMessage}", isError = true)
        }
        return 0
    }

    private fun editMessage(
        chatId: Long,
        messageId: Long,
        text: String,
        parseMode: String? = null,
        replyMarkup: String? = null
    ) {
        try {
            val cleanToken = botToken.trim()
            val url = "https://api.telegram.org/bot$cleanToken/editMessageText"
            val formBuilder = FormBody.Builder()
                .add("chat_id", chatId.toString())
                .add("message_id", messageId.toString())
                .add("text", text)

            if (!parseMode.isNullOrBlank()) {
                formBuilder.add("parse_mode", parseMode)
            }
            if (!replyMarkup.isNullOrBlank()) {
                formBuilder.add("reply_markup", replyMarkup)
            }

            val request = Request.Builder()
                .url(url)
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().close()
        } catch (e: Exception) {
            // Ignore edit failures
        }
    }

    private fun answerCallbackQuery(queryId: String) {
        try {
            val cleanToken = botToken.trim()
            val url = "https://api.telegram.org/bot$cleanToken/answerCallbackQuery"
            val form = FormBody.Builder().add("callback_query_id", queryId).build()
            val req = Request.Builder().url(url).post(form).build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun stop() {
        isRunning = false
    }
}
