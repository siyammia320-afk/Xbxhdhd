package com.example.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.service.BotForegroundService

object BotForegroundServiceUtils {

    fun startService(context: Context, token: String, scriptContent: String) {
        val intent = Intent(context, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_START
            putExtra(BotForegroundService.EXTRA_TOKEN, token)
            putExtra(BotForegroundService.EXTRA_SCRIPT, scriptContent)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopService(context: Context) {
        val intent = Intent(context, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }
}
