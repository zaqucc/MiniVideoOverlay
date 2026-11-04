package com.personal.minivideooverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder

/** Owns the long-running export independently from the Activity lifecycle. */
class ExportService : Service() {
    @Volatile private var engine: ExportEngine? = null
    @Volatile private var worker: Thread? = null
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ekspor video", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progres rendering MiniVideoOverlay"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                publish(ExportServiceState.progress, "Membatalkan ekspor…")
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (worker?.isAlive == true) return START_NOT_STICKY
                val video = intent.getStringExtra(EXTRA_VIDEO)?.let(Uri::parse)
                val png = intent.getStringExtra(EXTRA_PNG)?.let(Uri::parse)
                val music = intent.getStringExtra(EXTRA_MUSIC)?.let(Uri::parse)
                if (video == null || png == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val videoVolume = intent.getIntExtra(EXTRA_VIDEO_VOLUME, 100)
                val musicVolume = intent.getIntExtra(EXTRA_MUSIC_VOLUME, 100)
                ExportServiceState.isRunning = true
                ExportServiceState.progress = 0
                ExportServiceState.message = "Menyiapkan ekspor…"
                ExportServiceState.outputUri = null
                startAsForeground(buildNotification("Menyiapkan ekspor…", 0, false))
                val job = ExportEngine(applicationContext)
                engine = job
                worker = Thread({
                    try {
                        val result = job.export(video, png, music, videoVolume, musicVolume) { pct, message ->
                            publish(pct, message)
                        }
                        ExportServiceState.outputUri = result.uri.toString()
                        getSharedPreferences("mvo_export_state", MODE_PRIVATE).edit()
                            .putString("last_output_uri", result.uri.toString())
                            .putString("last_output_name", result.fileName)
                            .apply()
                        publish(100, "Selesai: ${result.fileName}")
                        notifications.notify(NOTIFICATION_ID, buildNotification("Ekspor selesai — video tersimpan", 100, true))
                        sendBroadcast(Intent(ACTION_FINISHED).setPackage(packageName).putExtra(EXTRA_OUTPUT, result.uri.toString()).putExtra(EXTRA_FILENAME, result.fileName))
                    } catch (error: Throwable) {
                        val message = if (error is ExportException) error.message ?: "Ekspor gagal" else "Ekspor gagal: ${error.message ?: error.javaClass.simpleName}"
                        ExportServiceState.message = message
                        getSharedPreferences("mvo_export_state", MODE_PRIVATE).edit().putString("last_error", message).apply()
                        sendBroadcast(Intent(ACTION_FAILED).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
                        notifications.notify(NOTIFICATION_ID, buildNotification(message, ExportServiceState.progress, true))
                    } finally {
                        ExportServiceState.isRunning = false
                        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
                        engine = null
                        worker = null
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                }, "mvo-foreground-export")
                worker?.start()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 35) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun publish(percent: Int, message: String) {
        ExportServiceState.progress = percent.coerceIn(0, 100)
        ExportServiceState.message = message
        notifications.notify(NOTIFICATION_ID, buildNotification(message, percent, false))
        sendBroadcast(Intent(ACTION_PROGRESS).setPackage(packageName).putExtra(EXTRA_PROGRESS, percent).putExtra(EXTRA_MESSAGE, message))
    }

    private fun buildNotification(message: String, percent: Int, completed: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (completed) "MiniVideoOverlay" else "MiniVideoOverlay • Rendering")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOngoing(!completed)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setCategory(Notification.CATEGORY_PROGRESS)
        if (!completed) {
            val cancelIntent = PendingIntent.getService(
                this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Batalkan", cancelIntent)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Do not cancel on Activity destruction. The service owns the job.
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.personal.minivideooverlay.EXPORT_START"
        const val ACTION_CANCEL = "com.personal.minivideooverlay.EXPORT_CANCEL"
        const val ACTION_PROGRESS = "com.personal.minivideooverlay.EXPORT_PROGRESS"
        const val ACTION_FINISHED = "com.personal.minivideooverlay.EXPORT_FINISHED"
        const val ACTION_FAILED = "com.personal.minivideooverlay.EXPORT_FAILED"
        const val ACTION_STATE_CHANGED = "com.personal.minivideooverlay.EXPORT_STATE_CHANGED"
        const val EXTRA_VIDEO = "video"; const val EXTRA_PNG = "png"; const val EXTRA_MUSIC = "music"
        const val EXTRA_VIDEO_VOLUME = "video_volume"; const val EXTRA_MUSIC_VOLUME = "music_volume"
        const val EXTRA_PROGRESS = "progress"; const val EXTRA_MESSAGE = "message"
        const val EXTRA_OUTPUT = "output"; const val EXTRA_FILENAME = "filename"
        private const val CHANNEL_ID = "mvo_export"
        private const val NOTIFICATION_ID = 2401
    }
}

object ExportServiceState {
    @Volatile var isRunning: Boolean = false
    @Volatile var progress: Int = 0
    @Volatile var message: String = ""
    @Volatile var outputUri: String? = null
}
