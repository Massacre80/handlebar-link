package com.abid.handlebarlink.bike.link.android.gatt

import android.bluetooth.BluetoothGatt
import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.android.GattStatus
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * The one GATT operation in flight (C1). Armed by the caller before the operation starts; completed by
 * a callback on the Bluetooth thread only if both the operation and the characteristic match, so a late
 * reply to a timed-out operation never completes the next one. Thread-safe.
 */
internal class OperationSlot {
    /** An armed operation; await [result]. */
    class Armed(
        val op: GattOp,
        val target: UUID?,
        val result: CompletableDeferred<Any?> = CompletableDeferred(),
    )

    private val current = AtomicReference<Armed?>(null)

    fun arm(
        op: GattOp,
        target: UUID?,
    ): Armed = Armed(op, target).also { current.set(it) }

    /** Clears [armed] if it is still the current one. */
    fun disarm(armed: Armed) {
        current.compareAndSet(armed, null)
    }

    /** A callback for [op] on [target] with [status]; a non-matching callback is ignored. */
    fun complete(
        op: GattOp,
        target: UUID?,
        status: Int,
        value: Any?,
    ) {
        val armed = current.get() ?: return
        if (armed.op != op || armed.target != target) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
            armed.result.complete(value)
        } else {
            armed.result.completeExceptionally(LinkException(GattStatus.errorFor(op, status)))
        }
    }

    /** The link went down: the waiting operation fails with [status]. */
    fun fail(status: Int) {
        current.getAndSet(null)?.result?.completeExceptionally(LinkException(LinkError.Disconnected(status)))
    }
}
