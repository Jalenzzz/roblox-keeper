package com.example.robloxkeeper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

//shoutout claude

class KeeperService : Service() {

    companion object {
        var isRunning = false
        val client = OkHttpClient()
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "keeper_channel"
        private const val TAG = "KeeperService"
        private const val TARGET_PACKAGE = "com.roblox.client"
        private const val DEEPLINK = "https://www.roblox.com/share?code=c00bc130c108844d800e144db1ed8352&type=Server"
        private const val CHECK_URL = "https://inventories.jailbreakchangelogs.com/bots/connected"
        private const val CHECK_INTERVAL = 10000L
        private const val HEARTBEAT_INTERVAL = 5 * 60 * 1000L // 5 minutes

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
    private var hasBeenForeground = false
    private var botId: String = "10811982647"
    private var logcatThread: Thread? = null

    private val timerTask = object : Runnable {
        override fun run() {
            Thread {
                val isRobloxForeground = isForegroundApp(TARGET_PACKAGE)
                handler.post {
                    Log.d(TAG, "Is Roblox in foreground? $isRobloxForeground")
                    logEvent("Check: robloxForeground=$isRobloxForeground")

                    if (isRobloxForeground) {
                        lastNotForeground = false
                        hasBeenForeground = true
                    } else {
                        if (lastNotForeground && hasBeenForeground) {
                            openDeeplink()
                            lastNotForeground = false
                            hasBeenForeground = false
                        } else {
                            lastNotForeground = true
                        }
                    }
                }
            }.start()

            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    private val heartbeatTask = object : Runnable {
        override fun run() {
            // Run network call on a background thread
            Thread {
                checkHeartbeat()
            }.start()

            handler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())

        // Safely read the bot ID file here instead of in companion object
        botId = try {
            val file = File(
                Environment.getExternalStorageDirectory(),
                "Delta/Workspace/BotId.txt"
            )
            file.readText().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read BotId.txt", e)
            logEvent("ERROR: Could not read BotId.txt — ${e.message}")
            ""
        }

        logEvent("Service started, bot ID: $botId")
        handler.post(timerTask)
        handler.post(heartbeatTask)
        startLogcatMonitor()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(timerTask)
        handler.removeCallbacks(heartbeatTask)
        logcatThread?.interrupt()
        clearSavedLogs(this)
        Log.d(TAG, "Service stopped and logs cleared")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun killRoblox() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $TARGET_PACKAGE"))
            Log.d(TAG, "Force-stopped $TARGET_PACKAGE via root")
            logEvent("Force-stopped $TARGET_PACKAGE via root shell")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill Roblox", e)
            logEvent("Failed to kill Roblox: ${e.message ?: "unknown"}")
        }
    }

    private fun checkHeartbeat() {
        if (botId.isBlank()) {
            logEvent("Heartbeat check skipped: no bot ID loaded")
            return
        }

        try {
            val request = Request.Builder()
                .url(CHECK_URL)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logEvent("Heartbeat check failed: HTTP ${response.code}")
                    return
                }

                val rawJson = response.body!!.string() // save it first
                val json = JSONObject(rawJson)         // then parse from the saved string
                val bots = json.getJSONArray("recent_heartbeats")

                var matchedBot: JSONObject? = null
                for (i in 0 until bots.length()) {
                    val bot = bots.getJSONObject(i)
                    if (bot.getString("id") == botId) {
                        matchedBot = bot
                        break
                    }
                }

                if (matchedBot != null) {
                    val lastHeartbeat = matchedBot.getLong("last_heartbeat")
                    val nowSeconds = System.currentTimeMillis() / 1000
                    val secondsAgo = nowSeconds - lastHeartbeat
                    val minutes = secondsAgo / 60
                    val seconds = secondsAgo % 60
                    val state = matchedBot.getString("client_state")

                    logEvent("Heartbeat: state=$state, last seen ${minutes}m ${seconds}s ago")

                    if (secondsAgo >= 150) {
                        logEvent("Heartbeat stale (${minutes}m ${seconds}s) — relaunching and killing Roblox")
                        handler.post {
                            openDeeplink()
                            hasBeenForeground = false
                            lastNotForeground = false
                        }
                        handler.postDelayed({ killRoblox() }, 2000)
                    } else Unit
                } else {
                    logEvent("Heartbeat: bot ID $botId not found — relaunching")
                    handler.post {
                        openDeeplink()
                        hasBeenForeground = false
                        lastNotForeground = false
                    }
                    handler.postDelayed({ killRoblox() }, 2000)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat check error", e)
            logEvent("Heartbeat check error: ${e.message ?: "unknown"}")
        }
    }

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

    private fun isForegroundApp(packageName: String): Boolean {
        var process: java.lang.Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys activity activities"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            val resumedLine = output.lineSequence().firstOrNull { it.contains("mResumedActivity") }
            Log.d(TAG, "mResumedActivity: $resumedLine")
            resumedLine?.contains(packageName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Foreground check failed", e)
            false
        } finally {
            process?.destroy()
        }
    }

    private fun openDeeplink() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "am start -a android.intent.action.VIEW -d \"$DEEPLINK\""))
            Log.d(TAG, "Launched deeplink via am start")
            logEvent("Relaunch triggered: $DEEPLINK")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open deeplink", e)
            logEvent("Relaunch failed: ${e.message ?: "unknown error"}")
        }
    }

    private fun startLogcatMonitor() {
        logcatThread = Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "logcat -s ActivityManager:I"))
                val reader = process.inputStream.bufferedReader()
                var line: String? = null
                while (!Thread.currentThread().isInterrupted && reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.contains(TARGET_PACKAGE) && (l.contains("died") || l.contains("crash"))) {
                        logEvent("Logcat: Roblox crash detected — relaunching")
                        handler.post {
                            openDeeplink()
                            lastNotForeground = false
                        }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.e(TAG, "Logcat monitor error", e)
                logEvent("Logcat monitor stopped: ${e.message}")
            }
        }
        logcatThread?.start()
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
