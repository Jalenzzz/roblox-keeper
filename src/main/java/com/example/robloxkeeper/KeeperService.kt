package com.example.robloxkeeper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KeeperService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "keeper_channel"
        private const val TAG = "KeeperService"
        private const val TARGET_PACKAGE = "com.roblox.client"
        private const val DEEPLINK = "roblox://placeId=606849621"
        private const val CHECK_INTERVAL = 10000L
        const val ACTION_LOG_UPDATE = "com.example.robloxkeeper.ACTION_LOG_UPDATE"
        const val EXTRA_LOG_TEXT = "extra_log_text"
        private const val PREFS_NAME = "keeper_prefs"
        private const val PREFS_KEY_LOGS = "service_logs"
        private const val MAX_LOG_LINES = 120

        fun getSavedLogs(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREFS_KEY_LOGS, "") ?: ""
        }

        fun clearSavedLogs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PREFS_KEY_LOGS).apply()
            context.sendBroadcast(
                Intent(ACTION_LOG_UPDATE)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_LOG_TEXT, "")
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotForeground = false

    private val timerTask = object : Runnable {
        override fun run() {
            val isRobloxForeground = isForegroundApp(TARGET_PACKAGE)
            Log.d(TAG, "Is Roblox in foreground? $isRobloxForeground")
            logEvent("Check: robloxForeground=$isRobloxForeground")

            if (isRobloxForeground) {
                lastNotForeground = false
            } else {
                if (lastNotForeground) {
                    openDeeplink()
                } else {
                    lastNotForeground = true
                }
            }

            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        logEvent("Service started")
        handler.post(timerTask)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(timerTask)
        clearSavedLogs(this)
        Log.d(TAG, "Service stopped and logs cleared")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Keeper Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Roblox Keeper")
            .setContentText("Monitoring Roblox status")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun isForegroundApp(packageName: String): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            val foregroundApp = processes?.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
            val currentPackage = foregroundApp?.processName
            Log.d(TAG, "Foreground process: $currentPackage")
            currentPackage == packageName
        } catch (e: Exception) {
            Log.e(TAG, "Foreground check failed", e)
            false
        }
    }

    private fun openDeeplink() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, DEEPLINK.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.d(TAG, "Opened Roblox deeplink")
            logEvent("Relaunch triggered: $DEEPLINK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open deeplink", e)
            logEvent("Relaunch failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val formatted = "[$timestamp] $message"

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREFS_KEY_LOGS, "") ?: ""
        val combined = if (existing.isBlank()) formatted else "$existing\n$formatted"
        val trimmed = combined
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
            .takeLast(MAX_LOG_LINES)
            .joinToString("\n")
        prefs.edit().putString(PREFS_KEY_LOGS, trimmed).apply()

        sendBroadcast(
            Intent(ACTION_LOG_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_LOG_TEXT, formatted)
        )
        Log.d(TAG, formatted)
    }

}
