package com.abid.handlebarlink

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.Test

/**
 * When the app on screen starts the ride (MainViewModel's [startsRideOnResume]): for the bike as before, and for our
 * CarPlay, which replaced CarLink, with or without a bike (owner, 2026-11-20).
 */
class RideAutoStartTest {
    @Test
    fun `the bike starts the ride once it is set up and Nearby devices is allowed`() {
        assertThat(startsRideOnResume(bikeChosen = true, nearbyGranted = true, false, false)).isTrue()
        assertThat(startsRideOnResume(bikeChosen = true, nearbyGranted = false, false, false)).isFalse()
        assertThat(startsRideOnResume(bikeChosen = false, nearbyGranted = true, false, false)).isFalse()
    }

    @Test
    fun `CarPlay starts the ride without a bike once an iPhone is paired and its permissions are allowed`() {
        assertThat(startsRideOnResume(false, false, carPlayWantsRide = true, carPlayGranted = true)).isTrue()
        // A missing permission: the service's start would not be allowed, and the tab asks for it instead.
        assertThat(startsRideOnResume(false, false, carPlayWantsRide = true, carPlayGranted = false)).isFalse()
        // No iPhone, or the rider stopped CarPlay: nothing to start a ride for.
        assertThat(startsRideOnResume(false, false, carPlayWantsRide = false, carPlayGranted = true)).isFalse()
    }
}
