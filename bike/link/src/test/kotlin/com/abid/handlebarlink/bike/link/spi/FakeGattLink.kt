package com.abid.handlebarlink.bike.link.spi

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.bike.protocol.toSabHex
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * A scripted CB350 meter behind [GattLink] (docs/research/02-ble-connection.md §6.2). By default it
 * answers like the real bike (SAB_PROTOCOL.md "Verified on the CB350"); the vars script faults.
 *
 * It enforces Android's rules: one operation in flight, and every write approved by [SabSafety].
 */
internal class FakeGattLink : GattLink {
    /** How a scripted operation behaves. */
    sealed interface Behaviour {
        data object Answer : Behaviour

        data class Fail(
            val status: Int,
        ) : Behaviour

        data object Never : Behaviour
    }

    private val notificationFlow = MutableSharedFlow<GattNotification>(extraBufferCapacity = 64)
    override val notifications: SharedFlow<GattNotification> = notificationFlow
    override val bondState = MutableStateFlow(BondState.BONDED)

    /** Simulated radio time per operation. */
    var opMillis = 30L
    var mtu: Behaviour = Behaviour.Answer
    var discover: Behaviour = Behaviour.Answer
    var services: Map<UUID, Set<UUID>> = CB350_TABLE
    val values: MutableMap<UUID, ByteArray> =
        mutableMapOf(
            SabUuids.SERIES_CODE to byteArrayOf(0xF5.toByte(), 0x03),
            SabUuids.DISPLAY_CAPABILITY to byteArrayOf(1),
        )
    var failingReads: Set<UUID> = emptySet()

    /** Assignment status per function code; 0x01 by default, 0x0D after a reconnect. */
    var assignmentStatus: (functionCode: Int) -> Int = { 0x01 }

    /** How many times each function code's request is ignored before the meter answers. */
    val silentAssignments: MutableMap<Int, Int> = mutableMapOf()

    /** Send an echo of the request and a reply to another id before the real reply (SAB §5.2). */
    var noisyReplies = false

    /** null: never answer REQUEST_SAB_MODE; else the receipt status. */
    var phoneModeReceipt: Int? = 0x01

    /** A write to these characteristics fails with this status. */
    var failingWrites: Map<UUID, Int> = emptyMap()

    val writes = mutableListOf<Pair<UUID, String>>()
    val subscribed = mutableListOf<UUID>()
    val operations = mutableListOf<GattOp>()
    private var inFlight = false

    /** The rider pressed the switch: the meter sends [code] on the function source. */
    fun press(code: Int) {
        notify(SabUuids.FUNCTION_SOURCE, byteArrayOf(code.toByte()))
    }

    override suspend fun requestMtu(mtu: Int): Int = op(GattOp.MTU, this.mtu) { mtu }

    override suspend fun discoverServices() = op(GattOp.DISCOVER, discover) {}

    override fun hasCharacteristic(
        service: UUID,
        characteristic: UUID,
    ): Boolean = services[service]?.contains(characteristic) == true

    override suspend fun read(
        service: UUID,
        characteristic: UUID,
    ): ByteArray {
        val behaviour = if (characteristic in failingReads) Behaviour.Fail(GATT_ERROR) else Behaviour.Answer
        return op(GattOp.READ, behaviour) { values.getValue(characteristic).copyOf() }
    }

    override suspend fun enableNotifications(
        service: UUID,
        characteristic: UUID,
    ) {
        check(SabSafety.isSubscribeAllowed(service)) { "subscribed to a forbidden service" }
        op(GattOp.ENABLE_NOTIFICATIONS, Behaviour.Answer) { subscribed += characteristic }
    }

    override suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(SabSafety.isWriteAllowed(service, characteristic, value)) { "forbidden write ${value.toSabHex()}" }
        val behaviour = failingWrites[characteristic]?.let { Behaviour.Fail(it) } ?: Behaviour.Answer
        op(GattOp.WRITE, behaviour) { writes += characteristic to value.toSabHex() }
        respond(characteristic, value)
    }

    private fun respond(
        characteristic: UUID,
        value: ByteArray,
    ) {
        when {
            characteristic == SabUuids.ASSIGNMENT_CONTROL && value.size > 3 -> answerAssignment(value)
            characteristic == SabUuids.DATA_CONTROL_POINT && value[0] == REQUEST_SAB_MODE -> answerPhoneMode(value)
        }
    }

    private fun answerAssignment(request: ByteArray) {
        val function = request[3].toInt()
        val silent = silentAssignments[function] ?: 0
        if (silent > 0) {
            silentAssignments[function] = silent - 1
            return
        }
        if (noisyReplies) {
            notify(SabUuids.ASSIGNMENT_CONTROL, request.copyOf())
            notify(SabUuids.ASSIGNMENT_CONTROL, byteArrayOf(1, (request[1] + 7).toByte(), request[2], 1))
        }
        notify(SabUuids.ASSIGNMENT_CONTROL, byteArrayOf(1, request[1], request[2], assignmentStatus(function).toByte()))
    }

    private fun answerPhoneMode(command: ByteArray) {
        val status = phoneModeReceipt ?: return
        notify(SabUuids.DATA_CONTROL_POINT, byteArrayOf(command[0], command[1], status.toByte()))
        // Verified on the CB350: the meter then reports phone mode off (10) and on (9).
        press(MODE_HU)
        press(MODE_SAB)
    }

    private fun notify(
        characteristic: UUID,
        value: ByteArray,
    ) {
        check(notificationFlow.tryEmit(GattNotification(characteristic, value))) { "notification buffer full" }
    }

    private suspend fun <T> op(
        op: GattOp,
        behaviour: Behaviour,
        result: () -> T,
    ): T {
        check(!inFlight) { "two GATT operations in flight ($op)" }
        inFlight = true
        try {
            operations += op
            delay(opMillis)
            return when (behaviour) {
                Behaviour.Answer -> result()
                is Behaviour.Fail -> throw LinkException(LinkError.GattFailed(op, behaviour.status))
                Behaviour.Never -> awaitCancellation()
            }
        } finally {
            inFlight = false
        }
    }

    companion object {
        const val GATT_ERROR = 133
        const val REQUEST_SAB_MODE: Byte = 0x0A
        const val MODE_SAB = 9
        const val MODE_HU = 10

        /** The CB350 meter's GATT table, as discovered on the bike (SAB_PROTOCOL.md §2.1). */
        val CB350_TABLE: Map<UUID, Set<UUID>> =
            mapOf(
                SabUuids.VEHICLE_INFO_SERVICE to
                    setOf(SabUuids.SERIES_CODE, SabUuids.DISPLAY_CAPABILITY, SabUuids.VEHICLE_TYPE),
                SabUuids.ASSIGNMENT_SERVICE to setOf(SabUuids.ASSIGNMENT_CONTROL),
                SabUuids.DATA_SERVICE to setOf(SabUuids.DATA_CONTROL_POINT, SabUuids.FUNCTION_SOURCE),
                SabUuids.DFU_SERVICE to setOf(SabUuids.DFU_FIRMWARE_VERSION),
            )
    }
}
