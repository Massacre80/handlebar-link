package com.abid.handlebarlink.ui.carplay

import com.abid.handlebarlink.carplay.IphonePairing
import com.abid.handlebarlink.carplay.engine.android.pairing.IphonePairingState
import com.abid.handlebarlink.carplay.engine.android.pairing.PairedIphone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/** Pairing as the test sets it; records choices, forgets and renames, and counts session collections. */
class FakeIphonePairing(
    iphones: List<PairedIphone>? = emptyList(),
    chosen: String? = null,
) : IphonePairing {
    val sessionState = MutableStateFlow<IphonePairingState>(IphonePairingState.Waiting("POCO F1"))
    val iphoneList = MutableStateFlow(iphones)
    val chosenAddress = MutableStateFlow(chosen)
    val chosenCalls = mutableListOf<String>()
    val forgotten = mutableListOf<String>()
    val renamed = mutableListOf<String>()
    var sessionsStarted = 0
    var sessionsActive = 0

    override fun session(): Flow<IphonePairingState> =
        sessionState
            .onStart {
                sessionsStarted++
                sessionsActive++
            }.onCompletion { sessionsActive-- }

    override fun iphones(): Flow<List<PairedIphone>?> = iphoneList

    override fun chosen(): Flow<String?> = chosenAddress

    override suspend fun choose(address: String) {
        chosenCalls += address
        chosenAddress.value = address
    }

    override suspend fun forget(address: String) {
        forgotten += address
        iphoneList.value = iphoneList.value?.filterNot { it.address == address }
        if (chosenAddress.value == address) chosenAddress.value = null
    }

    override suspend fun rename(name: String): Boolean {
        renamed += name
        return true
    }
}
