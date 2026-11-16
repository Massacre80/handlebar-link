package com.abid.handlebarlink.di

import com.abid.handlebarlink.core.common.log.Logger
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Dispatchers and scopes: the one place they are created (checkForbiddenCode allows only `di/`). */
@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {
    @Provides
    @Singleton
    @BikeLinkDispatcher
    fun bikeLinkDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1, "bike-link")

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Failures of one child never cancel the others; uncaught errors are logged, never swallowed silently. */
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(logger: Logger): CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, error -> logger.error("app.coroutine.uncaught", error) },
        )
}
