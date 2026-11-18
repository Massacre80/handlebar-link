package com.abid.handlebarlink.carplay.engine.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.HandlerThread
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What every port shares: the app context, one thread for system callbacks and broadcasts (never the main thread),
 * one thread for blocking storage work, the clock and the logger. Nothing starts until first used, so creating the
 * ports does no I/O on the caller's thread (docs/LESSONS.md 60).
 */
internal class AndroidPlatform(
    context: Context,
    val time: TimeProvider,
    val log: Logger,
) {
    val context: Context = context.applicationContext

    private val callbackThread by lazy { HandlerThread("carplay-platform").apply { start() } }

    /** System callbacks (Wi-Fi Direct, NSD, broadcasts) run here. */
    val handler: Handler by lazy { Handler(callbackThread.looper) }

    /** The same thread, as an [Executor]. */
    val executor: Executor = Executor { handler.post(it) }

    /** Blocking file and preference work, in order on one thread. */
    val ioExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor(daemonThreads("carplay-io")) }

    /** [ioExecutor] as a dispatcher. */
    val io: CoroutineDispatcher by lazy { ioExecutor.asCoroutineDispatcher() }

    /** Small settings (the chosen iPhone, the Wi-Fi Direct networks); read on [io] or a socket thread. */
    val preferences: SharedPreferences by lazy { this.context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }

    /** Secrets that must never be restored to another phone (the AirPlay identity, pairings). */
    val noBackupDirectory: File by lazy { File(this.context.noBackupFilesDir, NO_BACKUP_DIRECTORY) }

    companion object {
        const val PREFERENCES = "carplay_android"
        const val NO_BACKUP_DIRECTORY = "carplay"
    }
}
