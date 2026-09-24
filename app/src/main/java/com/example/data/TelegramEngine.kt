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

data class UserSession(
    var mode: String = "",       // "auto" or "manual"
    var step: String = "",       // "password", "email", "otp_wait", "otp_input"
    var email: String = "",
    var password: String = "",
    var tempToken: String = "",
    var otp: String = "",
    var uid: String = "",
    var cookie: String = "",
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

    // ================= KEYBOARDS (EXACT PYTHON SPEC) =================
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

    // ================= META ACCOUNT CREATION & CONFIRMATION =================
    data class MetaCreateResult(
        val success: Boolean,
        val uid: String = "",
        val message: String = "",
        val cookie: String = "",
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

    private suspend fun createTempMail(): TempMailData? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(TEMPMAIL_CREATE_URL)
                .post("".toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
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
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .header("Origin", "https://instanttempemail.com")
                .header("Referer", "https://instanttempemail.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val jsonArr = JSONArray(body)
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
            val formBuilder = FormBody.Builder()
                .add("consent_version", "")
                .add("contact_point_type", "EMAIL_ADDRESS")
                .add("csi", "Scd0BS3l-yOix38o6lNKa_kT")
                .add("date_of_birth", "1993-09-11")
                .add("device_id", "")
                .add("first_name", "Ajs")
                .add("last_name", "Sjs")
                .add("has_youth_consent", "false")
                .add("opt_into_marketing", "true")
                .add("password", pass)
                .add("reg_integrity", "Q8W2BTuKa24cQO_B6qvNeGtvmIjuAiCCCvXaCbgkwfWbqt-rPjXJArjnIu5K2myj2GHMJPPSr6BbjXBUFU17JuiZ3IvWTGB_fpfbXwr1sq6qX5lwBCHho2TWDE4ACpgpKGop91SIXofE0KTu2MBkdW1Ss0D6TG7isv6lz2N1CLlYDcRuoiMmSnzt_3_tNldGlheeYm1KVKQyQckdk6G2PoiceW2vxKWbivZ6HJPdq-QsNs4JB7yqEnYY2B3u6mfepC066IZYhv9ZgWpXIuYUsgim6pBbL6NyF84-UsOy8kYIOZlCHc_2PFK4SRPhdx0RMJipGYiIeb5y-Nwj_VHS1Tc2es-jc6aZ7hlBsBokGAeo-cnYEERpIKXz_OnmFTc48WHp2nMcJxww|kregenc")
                .add("should_save_credentials", "true")
                .add("waterfall_id", "701777af-c668-4a8d-976a-0c0f619d807a")
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
                .add("email", email)

            val request = Request.Builder()
                .url(TARGET_CREATE_URL)
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .header("Origin", "https://auth.meta.com")
                .header("Referer", "https://auth.meta.com/")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val cookies = response.headers("Set-Cookie").joinToString("; ") { it.split(";")[0] }

                // Parse uid and tokens
                val uidMatch = Pattern.compile("\"uid\":\\s*\"?(\\d+)\"?").matcher(body)
                val uid = if (uidMatch.find()) uidMatch.group(1) else ""

                val fbDtsgMatch = Pattern.compile("\\[\"DTSGInitialData\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}").matcher(body)
                val fbDtsg = if (fbDtsgMatch.find()) fbDtsgMatch.group(1) else ""

                val lsdMatch = Pattern.compile("\\[\"LSD\",\\[\\],\\{\"token\":\"([^\"]+)\"\\}").matcher(body)
                val lsd = if (lsdMatch.find()) lsdMatch.group(1) else "AdRLdRXnKs4_RAGnmEr-k2XaQu0"

                if (uid.isNotBlank()) {
                    MetaCreateResult(
                        success = true,
                        uid = uid,
                        cookie = cookies,
                        confirmLink = "https://auth.meta.com/confirm_email/?email=$email",
                        actorId = uid,
                        fbDtsg = fbDtsg,
                        lsd = lsd
                    )
                } else {
                    MetaCreateResult(success = false, message = "Could not obtain UID from Meta response")
                }
            }
        } catch (e: Exception) {
            MetaCreateResult(success = false, message = e.localizedMessage ?: "Unknown error")
        }
    }

    private suspend fun confirmMetaOtp(session: UserSession, otp: String): MetaConfirmResult = withContext(Dispatchers.IO) {
        try {
            val vPayload = JSONObject().apply {
                put("input", JSONObject().apply {
                    put("client_mutation_id", (1..9).map { ('a'..'z').random() }.joinToString(""))
                    put("actor_id", session.actorId.ifEmpty { session.uid })
                    put("contact_point", session.email)
                    put("contact_point_type", "EMAIL_ADDRESS")
                    put("confirmation_code", otp)
                })
            }

            val formBuilder = FormBody.Builder()
                .add("av", session.actorId.ifEmpty { session.uid })
                .add("__user", session.actorId.ifEmpty { session.uid })
                .add("__a", "1")
                .add("__req", "2")
                .add("__hs", "20707.HYP:frl_comet_auth_pkg.2.1...0")
                .add("dpr", "2")
                .add("__ccg", "MODERATE")
                .add("__rev", "1047236770")
                .add("fb_dtsg", session.fbDtsg)
                .add("jazoest", "22293")
                .add("lsd", session.lsd)
                .add("variables", vPayload.toString())
                .add("doc_id", "9851798224911796")

            val reqBuilder = Request.Builder()
                .url(TARGET_CONFIRM_URL)
                .post(formBuilder.build())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36")
                .header("Origin", "https://auth.meta.com")
                .header("x-fb-friendly-name", "FRLConfirmEmailMutation")

            if (session.cookie.isNotBlank()) {
                reqBuilder.header("Cookie", session.cookie)
            }

            client.newCall(reqBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val isConfirmed = body.contains("\"isConfirmed\":true") || body.contains("true")
                val accMatch = Pattern.compile("\"accountId\":\"(\\d+)\"").matcher(body)
                val accountId = if (accMatch.find()) accMatch.group(1) else session.uid

                MetaConfirmResult(
                    confirmed = isConfirmed,
                    uid = accountId
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
