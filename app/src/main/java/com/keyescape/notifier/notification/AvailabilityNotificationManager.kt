package com.keyescape.notifier.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keyescape.notifier.data.MonitoringGroupEntity
import com.keyescape.notifier.data.MonitoringSlotEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton class AvailabilityNotificationManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val channelId = "availability_alerts"
    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "취소표 알림", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    fun show(group: MonitoringGroupEntity, slot: MonitoringSlotEntity, url: String) {
        createChannel()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pending = PendingIntent.getActivity(context, slot.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val date = runCatching { LocalDate.parse(slot.date).format(DateTimeFormatter.ofPattern("M월 d일")) }.getOrDefault(slot.date)
        val content = "${group.branchName} · ${group.themeName}\n$date ${slot.desiredTime} 예약 가능"
        NotificationManagerCompat.from(context).notify(slot.id.hashCode(), NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("취소표가 생겼어요!").setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pending).build())
    }
}
