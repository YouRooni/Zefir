package dev.rooni.zefir

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.rooni.zefir.model.AppSettings
import dev.rooni.zefir.model.Profile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val dir = applicationContext.filesDir
        val settings = runCatching { json.decodeFromString<AppSettings>(File(dir, "settings.json").readText()) }.getOrNull() ?: return Result.success()
        if (!settings.reminders) return Result.success()
        val profiles = runCatching {
            json.decodeFromString(ListSerializer(Profile.serializer()), File(dir, "profiles.json").readText())
        }.getOrNull() ?: return Result.success()
        val profile = profiles.firstOrNull { it.id == settings.activeProfile } ?: return Result.success()

        val now = System.currentTimeMillis() / 1000
        val daysLeft = if (profile.expire > 0) (profile.expire - now) / 86_400 else Long.MAX_VALUE
        val almostOut = profile.total > 0 && profile.used.toDouble() / profile.total >= 0.9
        val text = when {
            daysLeft in 0..3 -> applicationContext.getString(R.string.reminder_days, profile.name, daysLeft.toInt())
            almostOut -> applicationContext.getString(R.string.reminder_traffic, profile.name)
            else -> return Result.success()
        }
        notify(text)
        return Result.success()
    }

    private fun notify(text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, applicationContext.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            applicationContext, 0, Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION_ID,
            android.app.Notification.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(applicationContext.getString(R.string.reminder_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val NOTIFICATION_ID = 8

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "subscription-reminder",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
