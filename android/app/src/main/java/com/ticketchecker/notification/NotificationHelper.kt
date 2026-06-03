package com.ticketchecker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ticketchecker.MainActivity
import com.ticketchecker.R

object NotificationHelper {

    private const val TAG = "NotificationHelper"

    const val CHANNEL_FOREGROUND = "foreground_channel"

    /** 일반 채널: 시스템 알림 설정을 그대로 따름 */
    private const val CHANNEL_ALERT_NORMAL = "ticket_alert_normal"

    /** 알람 채널: 무음/진동 모드 무시, 알람 볼륨 사용 */
    private const val CHANNEL_ALERT_ALARM = "ticket_alert_alarm"

    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val EXTRA_TAB = "extra_tab"
    const val TAB_INTERPARK = "인터파크"
    const val TAB_MELON = "멜론티켓"
    private var alertNotificationId = 2000

    // 진동 패턴: [대기, ON, 쉬기, ON, 쉬기, ON, 쉬기, ON] ms
    private val VIBRATION_TIMINGS = longArrayOf(0, 500, 150, 500, 150, 500, 150, 700)
    // 각 구간 세기 (0=꺼짐, 255=최대)
    private val VIBRATION_AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Foreground service channel
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FOREGROUND, "서비스 실행", NotificationManager.IMPORTANCE_LOW).apply {
                description = "티켓 체커 서비스가 실행 중입니다"
                setShowBadge(false)
            }
        )

        // 일반 알림 채널 (시스템 설정 따름)
        val normalSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val normalAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_NORMAL, "취소표 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "취소표 발생 및 세션 만료 알림"
                enableVibration(true)
                vibrationPattern = VIBRATION_TIMINGS
                setSound(normalSound, normalAttrs)
            }
        )

        // 알람 채널 (무음/진동 모드 무시)
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmAttrs = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT_ALARM, "취소표 알림 (무음 무시)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "무음/진동 모드에서도 알림"
                enableVibration(true)
                vibrationPattern = VIBRATION_TIMINGS
                setSound(alarmSound, alarmAttrs)
                if (nm.isNotificationPolicyAccessGranted) setBypassDnd(true)
            }
        )
    }

    /** 방해 금지 설정 화면 인텐트 */
    fun dndSettingsIntent() = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** 현재 설정에 맞는 채널 ID 반환 */
    private fun alertChannelId(context: Context): String =
        if (AlertSettings.isOverrideSilentMode(context)) CHANNEL_ALERT_ALARM
        else CHANNEL_ALERT_NORMAL

    /** 진동 직접 호출 — amplitude 제어 */
    private fun vibrateDirectly(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_TIMINGS, VIBRATION_AMPLITUDES, -1))
        } catch (e: Exception) {
            Log.w(TAG, "vibrateDirectly failed", e)
        }
    }

    fun buildForegroundNotification(context: Context): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context, 0,
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

    fun sendTicketAlert(context: Context, title: String, message: String, tab: String? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pendingIntent = PendingIntent.getActivity(
            context, alertNotificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                tab?.let { putExtra(EXTRA_TAB, it) }
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, alertChannelId(context))
            .setSmallIcon(R.drawable.ic_ticket)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(alertNotificationId++, notification)
        vibrateDirectly(context)
    }

    fun sendErrorAlert(context: Context, message: String) {
        sendTicketAlert(context, "티켓 체커 오류", message)
    }

    fun sendSessionExpiredAlert(context: Context, platform: String) {
        val tab = if (platform == TAB_INTERPARK) TAB_INTERPARK else TAB_MELON
        sendTicketAlert(context, "세션 만료 - $platform", "세션이 만료되었습니다. 앱을 열어 갱신해주세요.", tab)
    }

    fun sendCancelTicketAlert(context: Context, showName: String, detail: String, platform: String) {
        val tab = if (platform == TAB_INTERPARK) TAB_INTERPARK else TAB_MELON
        sendTicketAlert(context, "🎫 취소표 발생! - $platform", "$showName\n$detail", tab)
    }
}
