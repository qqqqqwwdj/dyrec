package com.dsh.dyrec.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.dsh.dyrec.MainActivity

object Notifications {
    const val CHANNEL_ID = "rec_channel"
    const val NOTIF_ID = 1001

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "录制服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "直播录制的进度与状态"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    fun build(ctx: Context, title: String, text: String, indeterminate: Boolean = true): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setProgress(0, 0, indeterminate)
            .build()
    }
}
