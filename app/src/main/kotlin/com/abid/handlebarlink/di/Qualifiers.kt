package com.abid.handlebarlink.di

import javax.inject.Qualifier

/** The single-threaded dispatcher all bike-link state is confined to. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BikeLinkDispatcher

/** The single-threaded dispatcher all CarPlay engine state is confined to; separate from the bike link's. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CarPlayDispatcher

/** For blocking calls (binder IPC to media sessions), never on the main thread (anr-audit-and-fix). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** A scope that lives as long as the app process (Now in Android's `@ApplicationScope`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
