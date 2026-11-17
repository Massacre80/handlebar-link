package com.abid.handlebarlink.bike.link.session

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.link.spi.GattCharacteristicInfo
import com.abid.handlebarlink.bike.link.spi.GattLink
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.bike.protocol.toSabHex
import com.abid.handlebarlink.core.common.log.Logger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
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

    /**
     * Listens to the meter's vendor services that RoadSync never uses (SAB_PROTOCOL.md "Probe run 1"):
     * turns on notifications (CCCD `01 00`) of exactly [VENDOR_NOTIFY] and logs every value they send
     * (`probe.notify`) until cancelled with the connection. The owner allowed only this write
     * (2026-11-17): nothing is ever written to these services' command characteristics.
     */
    suspend fun listen() {
        val targets =
            link.services().flatMap { service ->
                service.characteristics
                    .filter { (service.uuid to it.uuid) in VENDOR_NOTIFY && it.properties and PROPERTY_NOTIFY != 0 }
                    .map { service.uuid to it.uuid }
            }
        coroutineScope {
            // Collecting before subscribing: the first value may come at once.
            val listening =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    link.notifications
                        .filter { notification -> VENDOR_NOTIFY.any { it.second == notification.characteristic } }
                        .collect { notification ->
                            log.info(
                                "probe.notify",
                                "characteristic" to notification.characteristic,
                                "size" to notification.value.size,
                                "hex" to notification.value.toSabHex(),
                                "text" to printable(notification.value),
                            )
                        }
                }
            targets.forEach { (service, characteristic) ->
                try {
                    timed(GattOp.ENABLE_NOTIFICATIONS, Timeouts.OPERATION_MS) {
                        link.enableNotifications(service, characteristic)
                    }
                    log.info("probe.listen", "characteristic" to characteristic)
                } catch (e: LinkException) {
                    log.warn("probe.listen.failed", "characteristic" to characteristic, "error" to e.error)
                }
            }
            listening.join()
        }
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
        private const val PROPERTY_NOTIFY = 0x10

        /** The vendor services' characteristics that can notify (probe run 1); nothing else is subscribed. */
        val VENDOR_NOTIFY: Set<Pair<UUID, UUID>> =
            setOf(
                vendor("cc036c66-e0c9-42ce-b11f-257c546c5ac2", "85569fa7-6645-415d-8439-fb32f08e2133"),
                vendor("cc036c66-e0c9-42ce-b11f-257c546c5ac2", "3030ac98-582b-4c15-8ef2-e80ef8d303c8"),
                vendor("146a0176-b46f-48e7-ab74-70a7ce1a5341", "2573cb1a-7a02-45cd-957e-ebe6ab066539"),
                vendor("146a0176-b46f-48e7-ab74-70a7ce1a5341", "f7b4d188-ad35-424f-a473-000dcdcfd8fb"),
            )

        private fun vendor(
            service: String,
            characteristic: String,
        ) = UUID.fromString(service) to UUID.fromString(characteristic)

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
