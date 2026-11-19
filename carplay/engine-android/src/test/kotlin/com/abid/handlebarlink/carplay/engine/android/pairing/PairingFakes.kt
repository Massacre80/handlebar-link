package com.abid.handlebarlink.carplay.engine.android.pairing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.UUID

/** The iAP2 record, from CarLink's PhoneClassifier (an independent copy of the constant). */
internal val IAP2_RECORD: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")

/** Hands-free and A2DP sink records, as any phone or headset has (Bluetooth assigned numbers). */
internal val HANDS_FREE: UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
internal val AUDIO_SINK: UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")

/** Bluetooth as the test sets it; [emit] sends a broadcast to every collector of [events]. */
internal class FakePairingRadio(
    var allowed: Boolean = true,
    var on: Boolean = true,
    var name: String? = "POCO F1",
    var bonded: List<RadioDevice> = emptyList(),
    var renameWorks: Boolean = true,
) : PairingRadio {
    private val broadcasts = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 64)
    val fetched = mutableListOf<String>()
    val renamed = mutableListOf<String>()

    /** How many collectors are listening. */
    val listeners: Int get() = broadcasts.subscriptionCount.value

    fun emit(event: PairingEvent) {
        check(broadcasts.tryEmit(event))
    }

    override fun allowed(): Boolean = allowed

    override fun isOn(): Boolean = on

    override fun localName(): String? = name

    override fun rename(name: String): Boolean {
        renamed += name
        return renameWorks
    }

    override fun bonded(): List<RadioDevice> = if (allowed) bonded else emptyList()

    override fun fetchServices(address: String) {
        fetched += address
    }

    override fun events(): Flow<PairingEvent> = broadcasts
}

/** Choices in memory; [writes] counts what reached the "disk". */
internal class FakeChoiceStore(
    var stored: IphoneChoices = IphoneChoices(null, emptySet()),
) : IphoneChoiceStore {
    var reads = 0
    val writes = mutableListOf<IphoneChoices>()

    override fun read(): IphoneChoices {
        reads++
        return stored
    }

    override fun write(choices: IphoneChoices) {
        writes += choices
        stored = choices
    }
}
