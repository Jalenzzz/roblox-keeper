package com.example.robloxkeeper

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri

class KeeperService : Service() {

    companion object {
        var isRunning = false
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "keeper_channel"
        private const val TAG = "KeeperService"
        private const val TARGET_PACKAGE = "com.roblox.client"
        private const val DEEPLINK = "https://www.roblox.com/share?code=c00bc130c108844d800e144db1ed8352&type=Server"
        private const val CHECK_INTERVAL = 10000L
        private const val KILL_INTERVAL = 3600000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastNotForeground = false

    private val timerTask = object : Runnable {
        override fun run() {
            val isRobloxForeground = isForegroundApp(TARGET_PACKAGE)
            Log.d(TAG, "Is Roblox in foreground? $isRobloxForeground")

            if (!isRobloxForeground) {
                if (lastNotForeground) {
                    openDeeplink()
                } else {
                    lastNotForeground = true
                }
            } else {
                lastNotForeground = false
            }

            handler.postDelayed(this, CHECK_INTERVAL)
        }
    }

    private val killTask = object : Runnable {
        override fun run() {
            killTargetPackage(TARGET_PACKAGE)
            Log.d(TAG, "Killed target package: $TARGET_PACKAGE")
            handler.postDelayed(this, KILL_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, createNotification())
        handler.post(timerTask)
        handler.postDelayed(killTask, KILL_INTERVAL)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacks(timerTask)
        handler.removeCallbacks(killTask)
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open deeplink", e)
        }
    }

    private fun killTargetPackage(packageName: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            Runtime.getRuntime().exec("am force-stop $packageName")
            Log.d(TAG, "Force-stopped $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to kill package $packageName", e)
        }
    }
}
