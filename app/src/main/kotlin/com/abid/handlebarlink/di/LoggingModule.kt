package com.abid.handlebarlink.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.abid.handlebarlink.core.common.log.Level
import com.abid.handlebarlink.core.common.log.Logfmt
import com.abid.handlebarlink.core.common.log.Logger
import com.abid.handlebarlink.core.common.time.TimeProvider
import com.abid.handlebarlink.diagnostics.LogcatSink
import com.abid.handlebarlink.diagnostics.RingBufferSink
import com.abid.handlebarlink.time.AndroidTimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/** Time and logging (docs/research/01-architecture.md §3.2). */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {
    @Provides
    @Singleton
    fun timeProvider(): TimeProvider = AndroidTimeProvider()

    @Provides
    @Singleton
    fun ringBufferSink(): RingBufferSink = RingBufferSink()

    /** DEBUG to logcat in debuggable builds, INFO otherwise; everything to the in-app ring buffer. */
    @Provides
    @Singleton
    fun logger(
        @ApplicationContext context: Context,
        time: TimeProvider,
        ring: RingBufferSink,
    ): Logger {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val logcat = LogcatSink(if (debuggable) Level.DEBUG else Level.INFO, Logfmt(ZoneId.systemDefault()))
        return Logger("app", time.clock, listOf(logcat, ring))
    }
}
