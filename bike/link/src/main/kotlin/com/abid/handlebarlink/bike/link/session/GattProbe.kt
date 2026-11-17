package com.abid.handlebarlink.bike.link.session

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.link.spi.GattCharacteristicInfo
import com.abid.handlebarlink.bike.link.spi.GattLink
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.bike.protocol.toSabHex
import com.abid.handlebarlink.core.common.log.Logger
import java.util.UUID

/**
 * Logs everything the meter offers (BikeLinkConfig.probeGatt): every service, characteristic and
 * descriptor of the discovered table, and the value of every readable characteristic and every
 * descriptor. Read-only: it never writes or subscribes; [SabSafety.isReadAllowed] keeps it out of the DFU
 * service but its version, and the HID service is listed but not read (Android's). A failed read is logged
 * and the probe goes on; cancellation is not caught.
 */
internal class GattProbe(
    private val link: GattLink,
    private val log: Logger,
) {
    suspend fun run() {
        val table = link.services()
        log.info("probe.start", "services" to table.size, "characteristics" to table.sumOf { it.characteristics.size })
        table.forEach { service ->
            log.info("probe.service", "service" to service.uuid, "characteristics" to service.characteristics.size)
            if (service.uuid == HID_SERVICE) {
                // Listed, never read: Android keeps HID for its own HID host, and a refused read there left
                // BluetoothGatt busy, so every later operation on the link failed (bike, 2026-11-17).
                service.characteristics.forEach { listOnly(service.uuid, it, "android-hid") }
            } else {
                service.characteristics.forEach { probe(service.uuid, it) }
            }
        }
        log.info("probe.done")
    }

    private fun describe(
        service: UUID,
        characteristic: GattCharacteristicInfo,
    ) {
        log.info(
            "probe.characteristic",
            "service" to service,
            "characteristic" to characteristic.uuid,
            "properties" to propertyNames(characteristic.properties),
            "descriptors" to characteristic.descriptors,
        )
    }

    private fun listOnly(
        service: UUID,
        characteristic: GattCharacteristicInfo,
        reason: String,
    ) {
        describe(service, characteristic)
        log.info("probe.skipped", "characteristic" to characteristic.uuid, "reason" to reason)
    }

    private suspend fun probe(
        service: UUID,
        characteristic: GattCharacteristicInfo,
    ) {
        if (!SabSafety.isReadAllowed(service, characteristic.uuid)) {
            listOnly(service, characteristic, "dfu")
            return
        }
        describe(service, characteristic)
        if (characteristic.properties and PROPERTY_READ != 0) {
            value("probe.value", characteristic.uuid, null, GattOp.READ) { link.read(service, characteristic.uuid) }
        }
        characteristic.descriptors.forEach { descriptor ->
            value("probe.descriptor", characteristic.uuid, descriptor, GattOp.READ_DESCRIPTOR) {
                link.readDescriptor(service, characteristic.uuid, descriptor)
            }
        }
    }

    private suspend fun value(
        event: String,
        characteristic: UUID,
        descriptor: UUID?,
        op: GattOp,
        read: suspend () -> ByteArray,
    ) {
        try {
            val bytes = timed(op, Timeouts.OPERATION_MS) { read() }
            log.info(
                event,
                "characteristic" to characteristic,
                "descriptor" to descriptor,
                "size" to bytes.size,
                "hex" to bytes.toSabHex(),
                "text" to printable(bytes),
            )
        } catch (e: LinkException) {
            log.warn(
                "$event.failed",
                "characteristic" to characteristic,
                "descriptor" to descriptor,
                "error" to e.error,
            )
        }
    }

    internal companion object {
        /** Human Interface Device service (0x1812). */
        val HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")

        /** Bluetooth Core, Vol 3 Part G 3.3.1.1: the characteristic properties bits. */
        const val PROPERTY_READ = 0x02
        private val PROPERTIES =
            listOf(
                0x01 to "broadcast",
                PROPERTY_READ to "read",
                0x04 to "write-no-response",
                0x08 to "write",
                0x10 to "notify",
                0x20 to "indicate",
                0x40 to "signed-write",
                0x80 to "extended",
            )
        private const val FIRST_PRINTABLE = 0x20
        private const val LAST_PRINTABLE = 0x7E

        /** The property names set in [properties], e.g. "read,notify". */
        fun propertyNames(properties: Int): String =
            PROPERTIES.filter { (bit, _) -> properties and bit != 0 }.joinToString(",") { it.second }

        /** [bytes] as text when every byte is printable ASCII (trailing NULs dropped), else null. */
        fun printable(bytes: ByteArray): String? {
            val text = bytes.dropLastWhile { it == 0.toByte() }
            return if (text.isNotEmpty() && text.all { it.toInt() in FIRST_PRINTABLE..LAST_PRINTABLE }) {
                String(text.toByteArray(), Charsets.US_ASCII)
            } else {
                null
            }
        }
    }
}
