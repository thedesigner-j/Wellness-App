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
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "health-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.NONE).build(),
        )
    }

    companion object {
        const val CHANNEL_NUDGES = "nudges"

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
