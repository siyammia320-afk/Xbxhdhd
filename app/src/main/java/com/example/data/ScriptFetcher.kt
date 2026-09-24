package com.example.data

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object ScriptFetcher {
    // Encoded URL - completely hidden
    private const val ENCODED_ENDPOINT = "aHR0cHM6Ly9wYXN0ZWJpbi5jb20vcmF3L2IzNGE0ZWhM"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun getDecodedEndpoint(): String {
        return try {
            val decodedBytes = Base64.decode(ENCODED_ENDPOINT, Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun fetchAndValidateScript(): Pair<ScriptStatus, String> = withContext(Dispatchers.IO) {
        val endpoint = getDecodedEndpoint()
        if (endpoint.isBlank()) {
            return@withContext Pair(ScriptStatus.FAILED, "কনফিগারেশন লোড করা সম্ভব হয়নি")
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "Mozilla/5.0 (Android; TelegramBotClient)")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Pair(
                        ScriptStatus.FAILED,
                        "সার্ভার এরর: কোড ${response.code}"
                    )
                }

                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext Pair(
                        ScriptStatus.FAILED,
                        "সার্ভার থেকে কোনো রেসপন্স পাওয়া যায়নি"
                    )
                }

                // Check for BOT_TOKEN = "MY_BOT_TOKEN"
                val hasPlaceholder = body.contains("BOT_TOKEN = \"MY_BOT_TOKEN\"") ||
                        body.contains("BOT_TOKEN=\"MY_BOT_TOKEN\"") ||
                        body.contains("BOT_TOKEN = 'MY_BOT_TOKEN'") ||
                        body.contains("BOT_TOKEN='MY_BOT_TOKEN'")

                if (hasPlaceholder) {
                    Pair(ScriptStatus.OK, body)
                } else {
                    Pair(
                        ScriptStatus.FAILED,
                        "কনফিগারেশনে BOT_TOKEN = \"MY_BOT_TOKEN\" পাওয়া যায়নি"
                    )
                }
            }
        } catch (e: IOException) {
            Pair(
                ScriptStatus.FAILED,
                "সার্ভার সংযোগ বিচ্ছিন্ন (${e.localizedMessage ?: "নেটওয়ার্ক চেক করুন"})"
            )
        } catch (e: Exception) {
            Pair(
                ScriptStatus.FAILED,
                "সার্ভার ত্রুটি: ${e.localizedMessage ?: "অপ্রত্যাশিত সমস্যা"}"
            )
        }
    }

    fun injectBotToken(rawScript: String, userToken: String): String {
        val cleanToken = userToken.trim()
        var updatedScript = rawScript
        val targetReplacements = listOf(
            "BOT_TOKEN = \"MY_BOT_TOKEN\"",
            "BOT_TOKEN=\"MY_BOT_TOKEN\"",
            "BOT_TOKEN = 'MY_BOT_TOKEN'",
            "BOT_TOKEN='MY_BOT_TOKEN'"
        )

        for (target in targetReplacements) {
            if (updatedScript.contains(target)) {
                updatedScript = updatedScript.replace(target, "BOT_TOKEN = \"$cleanToken\"")
            }
        }
        return updatedScript
    }
}
