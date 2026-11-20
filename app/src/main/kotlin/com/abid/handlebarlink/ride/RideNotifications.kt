package com.abid.handlebarlink.ride

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.abid.handlebarlink.MainActivity
import com.abid.handlebarlink.R
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkProblem
import com.abid.handlebarlink.bike.link.LinkState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The ride's notifications: the ongoing one the foreground service needs, and a problem notification
 * when the ride ends because the rider has to act. Every PendingIntent is explicit and immutable
 * (android-intent-security).
 */
class RideNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        /** The ongoing ride notification with the [status] line (from [statusText]). */
        fun riding(
            @StringRes status: Int,
        ): Notification {
            createChannels()
            return NotificationCompat
                .Builder(context, CHANNEL_RIDE)
                .setSmallIcon(R.drawable.ic_stat_ride)
                .setContentTitle(context.getString(R.string.ride_notification_title))
                .setContentText(context.getString(status))
                .setContentIntent(openApp())
                .addAction(0, context.getString(R.string.ride_stop), stopRide())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** Replaces the ongoing notification's [status] line. */
        fun update(
            @StringRes status: Int,
        ) {
            post(ID_RIDE, riding(status))
        }

        /** Tells the rider what to do after the ride ended with [problem]. */
        fun showProblem(problem: LinkProblem) {
            createChannels()
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_PROBLEMS)
                    .setSmallIcon(R.drawable.ic_stat_ride)
                    .setContentTitle(context.getString(R.string.problem_title))
                    .setContentText(context.getString(problemText(problem)))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build()
            post(ID_PROBLEM, notification)
        }

        /** Removes the problem notification (a new ride starts). */
        fun clearProblem() {
            manager.cancel(ID_PROBLEM)
        }

        /** Posts [notification] when allowed: POST_NOTIFICATIONS is a runtime permission from Android 13. */
        private fun post(
            id: Int,
            notification: Notification,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            manager.notify(id, notification)
        }

        private fun createChannels() {
            manager.createNotificationChannelsCompat(
                listOf(
                    NotificationChannelCompat
                        .Builder(CHANNEL_RIDE, NotificationManagerCompat.IMPORTANCE_LOW)
                        .setName(context.getString(R.string.channel_ride))
                        .build(),
                    NotificationChannelCompat
                        .Builder(CHANNEL_PROBLEMS, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName(context.getString(R.string.channel_problems))
                        .build(),
                ),
            )
        }

        private fun openApp(): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun stopRide(): PendingIntent =
            PendingIntent.getService(
                context,
                0,
                RideService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Notification ids. */
        companion object {
            /** The ongoing ride notification (also the foreground service's). */
            const val ID_RIDE = 1
            private const val ID_PROBLEM = 2
            private const val CHANNEL_RIDE = "ride"
            private const val CHANNEL_PROBLEMS = "problems"
        }
    }

/** The ride notification's line for [state]. */
@StringRes
internal fun statusText(state: LinkState): Int =
    when (state) {
        LinkState.Stopped, LinkState.Idle -> {
            R.string.status_stopped
        }

        LinkState.WaitingForBike -> {
            R.string.status_waiting
        }

        is LinkState.Connecting, is LinkState.SettingUp -> {
            R.string.status_connecting
        }

        is LinkState.Ready -> {
            R.string.status_ready
        }

        is LinkState.Recovering -> {
            R.string.status_recovering
        }

        is LinkState.Blocked -> {
            when (state.error) {
                LinkError.BluetoothOff -> R.string.status_bluetooth_off
                LinkError.PermissionMissing -> R.string.problem_permission
                else -> R.string.problem_repair
            }
        }
    }

/** What the rider has to do for [problem]. */
@StringRes
internal fun problemText(problem: LinkProblem): Int =
    when (problem) {
        LinkProblem.PERMISSION_MISSING -> R.string.problem_permission
        LinkProblem.NEEDS_REPAIR -> R.string.problem_repair
        LinkProblem.BLUETOOTH_STUCK -> R.string.problem_bluetooth_stuck
    }
