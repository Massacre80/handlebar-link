package com.abid.handlebarlink.carplay.engine.android

/**
 * The rider's Bluetooth headset (the helmet intercom) for CarPlay's calls and Siri: its Bluetooth [address]
 * ("AA:BB:…") and its own Bluetooth [name] ("EJEAS V6 Pro+"), either one optional. CarPlay prefers the connected
 * hands-free device that matches the address, then the name, then any connected Bluetooth headset; with no headset
 * connected, calls and Siri stay on the phone (docs/PLAN.md "C5 audio", "Routes").
 */
public data class CarPlayHeadset(
    val address: String? = null,
    val name: String? = null,
)

/**
 * Where the rider wants calls and Siri, read when a call's or Siri's sound or microphone starts (on an audio thread:
 * it must return at once, never block or do I/O). Null: the rider chose the phone itself for calls, so CarPlay
 * leaves the route to Android (the phone's own fallback).
 */
public fun interface CarPlayHeadsetSource {
    /** The headset to prefer now, or null for the phone itself. */
    public fun preferred(): CarPlayHeadset?

    /** Ready-made sources. */
    public companion object {
        /** Any connected Bluetooth headset, for an app that does not know the rider's devices. */
        public val ANY: CarPlayHeadsetSource = CarPlayHeadsetSource { CarPlayHeadset() }
    }
}
