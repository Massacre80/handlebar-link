package com.abid.handlebarlink.garage

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.abid.handlebarlink.MainActivity
import com.abid.handlebarlink.R
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import javax.inject.Inject

// The daily reminder check (docs/PLAN.md "Garage"): WorkManager runs it about once a day, also after a reboot (its
// schedule is persisted), and it notifies on the "Reminders" channel what has come due (planAlerts). An exact time is
// not needed, so no exact alarm (Android "Schedule alarms": prefer WorkManager for deferrable, persistent work).

/** Schedules the daily check; idempotent (the unique work is kept when it already exists). */
class GarageSchedule
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Makes sure the check runs once a day from now on. Touches WorkManager's database: off the main thread. */
        fun ensure() {
            WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<GarageReminderWorker>(1, TimeUnit.DAYS).build(),
                )
        }

        private companion object {
            const val WORK_NAME = "garage-reminders"
        }
    }

/** What the worker needs from Hilt; workers are built by WorkManager's default factory, not by Hilt. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarageWorkerEntryPoint {
    /** Garage's records. */
    fun store(): GarageStore

    /** The notifications. */
    fun notifications(): GarageNotifications

    /** The clock. */
    fun time(): TimeProvider

    /** The log. */
    fun logger(): Logger
}

/**
 * The daily check: works out what is due today, notifies what has reached a new stage, and stores what it notified.
 * The marks are stored first: a stage is notified only once it is recorded, so a check that cannot write never
 * notifies the same thing every day. Without notification permission nothing is marked, so allowing it later still
 * brings the reminder.
 */
class GarageReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, GarageWorkerEntryPoint::class.java)
        val notifications = deps.notifications()
        return if (notifications.allowed()) check(deps, notifications) else Result.success()
    }

    private suspend fun check(
        deps: GarageWorkerEntryPoint,
        notifications: GarageNotifications,
    ): Result {
        val today = deps.time().clock.today()
        var plan: AlertPlan? = null
        try {
            deps.store().update { data ->
                planAlerts(garageStatus(data, today).allDues(), data.alerts)
                    .also { plan = it }
                    .let { data.copy(alerts = it.marks) }
            }
        } catch (e: IOException) {
            deps.logger().error("garage.alerts_unsaved", e)
            return Result.retry()
        }
        plan?.notify?.forEach { (due, stage) -> notifications.show(due, stage) }
        deps.logger().info("garage.alerts", "notified" to (plan?.notify?.size ?: 0))
        return Result.success()
    }
}

/** Garage's notifications, on their own "Reminders" channel. Every PendingIntent is explicit and immutable. */
class GarageNotifications
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        /** Whether the app may post notifications (POST_NOTIFICATIONS is a runtime permission from Android 13). */
        fun allowed(): Boolean =
            manager.areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

        /** Tells the rider [due] has reached [stage]; one notification per reminder, replaced by the next stage. */
        fun show(
            due: Due,
            stage: AlertStage,
        ) {
            manager.createNotificationChannel(
                NotificationChannelCompat
                    .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_reminders))
                    .build(),
            )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_ride)
                    .setContentTitle(title(due, stage))
                    .setContentText(text(due))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            manager.notify(due.id, ID, notification)
        }

        private fun title(
            due: Due,
            stage: AlertStage,
        ): String {
            val name = due.name ?: context.getString(reminderName(due.kind))
            return when (stage) {
                AlertStage.OVERDUE -> context.getString(R.string.garage_alert_overdue, name)
                AlertStage.SOON, AlertStage.WEEK -> context.getString(R.string.garage_alert_soon, name)
            }
        }

        private fun text(due: Due): String {
            val day = due.dueDay?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            val km = due.dueKm?.let { context.getString(R.string.garage_km, grouped(it)) }
            return when {
                day != null && km != null -> context.getString(R.string.garage_alert_by_either, day, km)
                day != null -> context.getString(R.string.garage_alert_by_day, day)
                km != null -> context.getString(R.string.garage_alert_by_km, km)
                else -> ""
            }
        }

        private fun openApp(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private companion object {
            const val CHANNEL = "reminders"

            /** With the reminder's id as the tag, one notification per reminder. */
            const val ID = 100
        }
    }
