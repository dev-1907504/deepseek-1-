package com.dreamgirl.app.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dreamgirl.app.MainActivity
import com.dreamgirl.app.R

object Notifications {

    const val CHANNEL_HER = "her_messages"
    const val CHANNEL_SERVICE = "her_service"
    const val ID_NEW_MESSAGE = 1001
    const val ID_SERVICE = 1002

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_HER,
                "她发来的消息",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "她主动来找你说话时提醒你"
                enableLights(true)
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "常驻（她在看着你的作息）",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "让你知道她还在，同时避免被系统杀掉"
                setShowBadge(false)
            },
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 她主动说的那句话，直接进通知栏。 */
    fun showHerMessage(
        context: Context,
        text: String,
        reasonLabel: String,
        title: String = "dream",
    ) {
        // 显式检查（lint 认这个模式），下面还有 SecurityException 兜底：
        // 用户在系统里撤销通知权限后，我们宁可少发一条，也绝不让聊天流程崩掉。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!canPost(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_HER)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull()?.take(80) ?: text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSubText(reasonLabel)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_NEW_MESSAGE, notification)
        } catch (e: SecurityException) {
            android.util.Log.w("Notifications", "没有通知权限，这条就不发了", e)
        }
    }

    fun serviceNotification(
        context: Context,
        text: String,
        title: String = "dream",
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(context))
            .build()
}
