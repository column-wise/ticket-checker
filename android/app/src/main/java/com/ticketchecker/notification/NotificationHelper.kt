package com.ticketchecker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.ticketchecker.MainActivity
import com.ticketchecker.R

object NotificationHelper {

    const val CHANNEL_FOREGROUND = "foreground_channel"
    const val CHANNEL_ALERT = "ticket_alert_channel"
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val EXTRA_TAB = "extra_tab"
    const val TAB_INTERPARK = "인터파크"
    const val TAB_MELON = "멜론티켓"
    private var alertNotificationId = 2000

    fun createChannels(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Foreground service channel (low importance)
        val foregroundChannel = NotificationChannel(
            CHANNEL_FOREGROUND,
            "서비스 실행",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "티켓 체커 서비스가 실행 중입니다"
            setShowBadge(false)
        }

        // Alert channel (high importance, sound + vibration)
        val alertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "취소표 알림",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "취소표 발생 및 세션 만료 알림"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 300)
            setSound(alertSoundUri, audioAttributes)
        }

        notificationManager.createNotificationChannel(foregroundChannel)
        notificationManager.createNotificationChannel(alertChannel)
    }

    fun buildForegroundNotification(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
            .setSmallIcon(R.drawable.ic_ticket)
            .setContentTitle("🎫 티켓 체커 실행 중")
            .setContentText("취소표를 모니터링하고 있습니다")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun sendTicketAlert(
        context: Context,
        title: String,
        message: String,
        tab: String? = null
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context,
            alertNotificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                tab?.let { putExtra(EXTRA_TAB, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_ticket)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(alertNotificationId++, notification)
    }

    fun sendErrorAlert(context: Context, message: String) {
        sendTicketAlert(context, "티켓 체커 오류", message)
    }

    fun sendSessionExpiredAlert(context: Context, platform: String) {
        val tab = if (platform == TAB_INTERPARK) TAB_INTERPARK else TAB_MELON
        sendTicketAlert(
            context,
            "세션 만료 - $platform",
            "세션이 만료되었습니다. 앱을 열어 갱신해주세요.",
            tab
        )
    }

    fun sendCancelTicketAlert(
        context: Context,
        showName: String,
        detail: String,
        platform: String
    ) {
        val tab = if (platform == TAB_INTERPARK) TAB_INTERPARK else TAB_MELON
        sendTicketAlert(
            context,
            "🎫 취소표 발생! - $platform",
            "$showName\n$detail",
            tab
        )
    }
}
