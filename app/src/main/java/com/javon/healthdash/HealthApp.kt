package com.javon.healthdash

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class HealthApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGES, "Nudges", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Post-meal walk reminders"
            },
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_TRAINING, "Training reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Morning rundown and afternoon check-in for today's sessions"
            },
        )
        scheduleTrainingReminder(this, TrainingReminderWorker.MORNING, 7)
        scheduleTrainingReminder(this, TrainingReminderWorker.CHECK_IN, 16)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "health-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build(),
        )
    }

    companion object {
        const val CHANNEL_NUDGES = "nudges"
        const val CHANNEL_TRAINING = "training"

        /** Daily reminder at about [hour]:00 local time (WorkManager may run it a little later). */
        fun scheduleTrainingReminder(context: Context, slot: String, hour: Int) {
            val now = java.time.ZonedDateTime.now()
            var next = now.withHour(hour).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "training-$slot",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrainingReminderWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(java.time.Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                    .setInputData(workDataOf("slot" to slot))
                    .build(),
            )
        }

        fun container(context: Context) = (context.applicationContext as HealthApp).container

        /** Post-meal walk nudge, ~10 min after logging (SPEC §9). */
        fun scheduleWalkNudge(context: Context, mealTitle: String, delayMin: Long = 10) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<WalkNudgeWorker>()
                    .setInitialDelay(delayMin, TimeUnit.MINUTES)
                    .setInputData(workDataOf("title" to mealTitle))
                    .build(),
            )
        }
    }
}

/** Background sync so late CGM data lands even when the app is closed. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = HealthApp.container(applicationContext)
        if (!c.prefs.state.value.onboardingDone || c.prefs.state.value.sampleData) return Result.success()
        return runCatching { c.syncNow() }.fold({ Result.success() }, { Result.retry() })
    }
}

class WalkNudgeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!HealthApp.container(ctx).prefs.state.value.walkNudge) return Result.success()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val n = NotificationCompat.Builder(ctx, HealthApp.CHANNEL_NUDGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time for a short walk")
            .setContentText("${inputData.getString("title") ?: "Meal"} logged 10 min ago. 1,000 steps in the next 35 min counts.")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify((System.currentTimeMillis() % 100000).toInt(), n)
        return Result.success()
    }
}

/** Morning rundown (~7 AM) and afternoon check-in (~4 PM, only if something is still open) for today's training. */
class TrainingReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val c = HealthApp.container(ctx)
        val prefs = c.prefs.state.value
        val slot = inputData.getString("slot") ?: MORNING
        if (!prefs.onboardingDone || prefs.sampleData) return Result.success()
        if (slot == MORNING && !prefs.trainingMorning || slot == CHECK_IN && !prefs.trainingCheckIn) return Result.success()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        // Late runs (Doze) shouldn't send a stale message.
        if (slot == MORNING && now.hour >= 14 || slot == CHECK_IN && (now.hour < 13 || now.hour >= 21)) return Result.success()
        // Pull the latest Garmin workouts first so a run done this morning counts as done.
        if (slot == CHECK_IN) runCatching { c.syncNow() }
        val today = now.toLocalDate()
        val summary = com.javon.healthdash.data.todaySummary(
            c.dao.plannedNow(), com.javon.healthdash.data.GarminApiSource.dedup(c.dao.workoutsNow(today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())), today, zone,
        )
        val (title, text) = when (slot) {
            MORNING -> "Today's training" to (summary.morningText() ?: return Result.success())
            else -> "Still to do today" to (summary.checkInText()?.removePrefix("Still to do today: ") ?: return Result.success())
        }
        val open = android.app.PendingIntent.getActivity(
            ctx, 0, ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, HealthApp.CHANNEL_TRAINING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(if (slot == MORNING) 7001 else 7002, n)
        return Result.success()
    }

    companion object {
        const val MORNING = "morning"
        const val CHECK_IN = "checkin"
    }
}
