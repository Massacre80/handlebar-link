package com.abid.handlebarlink.carplay

import com.abid.handlebarlink.carplay.engine.spi.CarPlayPorts

/**
 * Where the CarPlay engine's platform ports come from: the seam between the app and `:carplay:engine-android`
 * (docs/PLAN.md "CarPlay receiver", C2). `di/CarPlayModule` binds it; until the Android ports exist the binding
 * is a placeholder that returns null.
 *
 * [create] is called once, when Hilt first builds the CarPlay link (the app's coordinators are built by view
 * models, so this can be the main thread): it must only construct objects, with no I/O and no Binder calls
 * (docs/LESSONS.md 60). The real work belongs in the ports' suspending calls, which the engine makes on its own
 * dispatcher while CarPlay runs.
 */
fun interface CarPlayPortsSource {
    /**
     * The ports for this phone, or null when no platform ports are available (this build has none): CarPlay then
     * shows a clear "not available" state and never runs.
     */
    fun create(): CarPlayPorts?
}
