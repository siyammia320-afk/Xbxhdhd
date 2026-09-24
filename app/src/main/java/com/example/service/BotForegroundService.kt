package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.BotForegroundServiceUtils
import com.example.data.BotRunningState
import com.example.data.BotStateRepository
import com.example.data.ScriptFetcher
import com.example.data.TelegramEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BotForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var botJob: Job? = null
    private var telegramEngine: TelegramEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "telegram_bot_running_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"
        const val EXTRA_TOKEN = "extra_bot_token"
        const val EXTRA_SCRIPT = "extra_script_content"
    }

    override fun onCreate() {
        super.onCreate()
        BotStateRepository.init(this)
        createNotificationChannel()

        // Acquire partial wakelock to ensure polling continues when phone screen is locked
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TelegramBot::WakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            // Ignore if wakelock fails
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                stopBotService()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val token = intent?.getStringExtra(EXTRA_TOKEN) ?: BotStateRepository.getSavedBotToken()
                val script = intent?.getStringExtra(EXTRA_SCRIPT) ?: BotStateRepository.rawScriptContent.value
                startBotService(token, script)
            }
        }

        return START_STICKY
    }

    private fun startBotService(token: String, script: String) {
        if (token.isBlank()) {
            BotStateRepository.addLog("সতর্কতা", "বট টোকেন পাওয়া যায়নি", isError = true)
            stopSelf()
            return
        }

        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours max
        val notification = buildForegroundNotification("বট চালু হচ্ছে...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val finalScript = ScriptFetcher.injectBotToken(script, token)
        BotStateRepository.saveBotToken(token)

        botJob?.cancel()
        telegramEngine?.stop()

        botJob = serviceScope.launch {
            val engine = TelegramEngine(
                botToken = token,
                scriptContent = finalScript,
                onLog = { entry ->
                    BotStateRepository.addLogEntry(entry)
                    if (entry.isIncoming) {
                        BotStateRepository.totalProcessedMessages.value += 1
                        updateNotification(
                            "বট সক্রিয় | ${BotStateRepository.totalProcessedMessages.value} টি মেসেজ হ্যান্ডল করা হয়েছে"
                        )
                    }
                },
                onStateChange = { state, info ->
                    BotStateRepository.botRunningState.value = state
                    BotStateRepository.botInfo.value = info
                    if (state == BotRunningState.RUNNING && info != null) {
                        updateNotification("@${info.username} ব্যাকগ্রাউন্ডে সক্রিয় আছে")
                    } else if (state == BotRunningState.ERROR) {
                        updateNotification("বট সংযোগ বিচ্ছিন্ন হয়েছে")
                    }
                }
            )
            telegramEngine = engine
            engine.startBotLoop()
        }
    }

    private fun stopBotService() {
        telegramEngine?.stop()
        botJob?.cancel()
        BotStateRepository.botRunningState.value = BotRunningState.STOPPED
        BotStateRepository.botInfo.value = null
        BotStateRepository.addLog("সিস্টেম", "ব্যাকগ্রাউন্ড বট সার্ভিস বন্ধ করা হয়েছে")

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Telegram Bot Runner",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "বট ব্যাকগ্রাউন্ডে চালু রাখার জন্য স্থায়ী নোটিফিকেশন"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BotForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 Telegram Bot Host")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বট বন্ধ করুন (Stop)", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildForegroundNotification(statusText))
    }

    override fun onDestroy() {
        super.onDestroy()
        telegramEngine?.stop()
        botJob?.cancel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
