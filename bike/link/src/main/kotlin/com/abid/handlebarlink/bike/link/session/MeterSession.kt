package com.abid.handlebarlink.bike.link.session

import com.abid.handlebarlink.bike.link.GattOp
import com.abid.handlebarlink.bike.link.LinkError
import com.abid.handlebarlink.bike.link.LinkException
import com.abid.handlebarlink.bike.link.MeterInfo
import com.abid.handlebarlink.bike.link.SetupStep
import com.abid.handlebarlink.bike.link.gatt.BondState
import com.abid.handlebarlink.bike.link.gatt.GattLink
import com.abid.handlebarlink.bike.link.machine.Timeouts
import com.abid.handlebarlink.bike.protocol.AssignmentRequestIds
import com.abid.handlebarlink.bike.protocol.Assignments
import com.abid.handlebarlink.bike.protocol.CommandRequestIds
import com.abid.handlebarlink.bike.protocol.DataCommands
import com.abid.handlebarlink.bike.protocol.DisplayCapability
import com.abid.handlebarlink.bike.protocol.FunctionEvent
import com.abid.handlebarlink.bike.protocol.FunctionSource
import com.abid.handlebarlink.bike.protocol.SabSafety
import com.abid.handlebarlink.bike.protocol.SabUuids
import com.abid.handlebarlink.bike.protocol.SeriesCode
import com.abid.handlebarlink.core.log.Logger
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * One meter setup on a connected [link], in RoadSync's order (docs/protocol/SAB_PROTOCOL.md §4.2),
 * plus the decoded switch events. Failures are thrown as [LinkException]; cancellation is never caught.
 */
internal class MeterSession(
    private val link: GattLink,
    private val log: Logger,
) {
    private val exchange = Exchange(link, log)

    /** Presses and meter-mode changes from the Function Notification Source (§5.3). */
    val events: Flow<FunctionEvent> =
        link.notifications
            .filter { it.characteristic == SabUuids.FUNCTION_SOURCE }
            .map { FunctionSource.decode(it.value) }

    /** Sets the meter up; reports each step to [onStep] and returns what the meter reported. */
    suspend fun setUp(onStep: (SetupStep) -> Unit): MeterInfo {
        onStep(SetupStep.BOND)
        awaitBond()
        onStep(SetupStep.MTU)
        requestMtu()
        onStep(SetupStep.DISCOVER)
        discover()
        onStep(SetupStep.VEHICLE_INFO)
        val meter = readVehicleInfo()
        onStep(SetupStep.NOTIFICATIONS)
        NOTIFIED.forEach { (service, characteristic) -> enableNotifications(service, characteristic) }
        onStep(SetupStep.ASSIGNMENTS)
        registerAssignments()
        onStep(SetupStep.PHONE_MODE)
        requestPhoneMode()
        return meter
    }

    /** No GATT work while bonding is in progress (C6, SAB §1.4). */
    private suspend fun awaitBond() {
        if (link.bondState.value != BondState.BONDING) return
        log.info("link.setup.bond.wait")
        timed(GattOp.SETUP, Timeouts.BOND_SETTLE_MS) { link.bondState.first { it != BondState.BONDING } }
    }

    /** A failed MTU exchange is tolerated, as in RoadSync (SAB §1.5); our frames are ≤ 20 bytes. */
    private suspend fun requestMtu() {
        try {
            val mtu = timed(GattOp.MTU, Timeouts.MTU_MS) { link.requestMtu(Timeouts.MTU) }
            log.debug("link.setup.mtu", "mtu" to mtu)
        } catch (e: LinkException) {
            log.warn("link.setup.mtu.failed", "error" to e.error)
        }
        delay(Timeouts.AFTER_MTU_MS)
    }

    private suspend fun discover() {
        timed(GattOp.DISCOVER, Timeouts.DISCOVER_MS) { link.discoverServices() }
        val missing =
            REQUIRED.filterNot { (service, characteristic) ->
                link.hasCharacteristic(service, characteristic)
            }
        if (missing.isNotEmpty()) {
            log.warn("link.setup.services.missing", "missing" to missing.map { it.second })
            throw LinkException(LinkError.ServicesMissing)
        }
    }

    /** Vehicle information is informational: a failed read leaves the field null (§1.2, §2.1). */
    private suspend fun readVehicleInfo(): MeterInfo {
        val series = readOrNull(SabUuids.SERIES_CODE)?.let(SeriesCode::decode)
        val display = readOrNull(SabUuids.DISPLAY_CAPABILITY)?.let(DisplayCapability::decode)
        if (series != null && !series.isCb350) log.warn("link.setup.not_cb350", "series" to series.hex)
        log.info("link.setup.vehicle", "series" to series?.hex, "display" to display)
        return MeterInfo(series, display)
    }

    private suspend fun readOrNull(characteristic: UUID): ByteArray? {
        if (!link.hasCharacteristic(SabUuids.VEHICLE_INFO_SERVICE, characteristic)) return null
        return try {
            timed(GattOp.READ, Timeouts.OPERATION_MS) { link.read(SabUuids.VEHICLE_INFO_SERVICE, characteristic) }
        } catch (e: LinkException) {
            log.warn("link.setup.read.failed", "characteristic" to characteristic, "error" to e.error)
            null
        }
    }

    private suspend fun enableNotifications(
        service: UUID,
        characteristic: UUID,
    ) {
        check(SabSafety.isSubscribeAllowed(service)) { "subscribing to $service is forbidden" }
        timed(GattOp.ENABLE_NOTIFICATIONS, Timeouts.OPERATION_MS) { link.enableNotifications(service, characteristic) }
    }

    /** The 10 CB350 assignments, each up to 5 tries, then END (§5.2). 1 and 13 are accepted. */
    private suspend fun registerAssignments() {
        Assignments.forCb350(AssignmentRequestIds()).forEach { assignment ->
            val result =
                exchange.retrying(Timeouts.ASSIGNMENT_TRIES, "assignment" to assignment.function) {
                    exchange.request(SabUuids.ASSIGNMENT_SERVICE, SabUuids.ASSIGNMENT_CONTROL, assignment.frame) {
                        Assignments.match(assignment, it)
                    }
                } ?: throw LinkException(LinkError.TimedOut(GattOp.ASSIGNMENT_REPLY))
            if (!result.status.isAccepted) {
                throw LinkException(LinkError.MeterRefused(assignment.function, result.status))
            }
        }
        exchange.write(SabUuids.ASSIGNMENT_SERVICE, SabUuids.ASSIGNMENT_CONTROL, Assignments.END.toByteArray())
    }

    /**
     * REQUEST_SAB_MODE (§5.4). An unconfirmed request is logged, not fatal: the meter answers with
     * mode events (10 then 9 on the CB350), which [events] reports.
     */
    private suspend fun requestPhoneMode() {
        val command = DataCommands.requestSabMode(CommandRequestIds())
        val receipt =
            exchange.retrying(Timeouts.COMMAND_TRIES, "command" to command.id) {
                exchange.request(SabUuids.DATA_SERVICE, SabUuids.DATA_CONTROL_POINT, command.frame) {
                    DataCommands.match(command, it)
                }
            }
        when {
            receipt == null -> log.warn("link.setup.phone_mode.unconfirmed")
            !receipt.isOk -> log.warn("link.setup.phone_mode.refused", "status" to receipt.status)
            else -> log.debug("link.setup.phone_mode.ok")
        }
    }

    private companion object {
        /** Characteristics the setup cannot do without. */
        val REQUIRED =
            listOf(
                SabUuids.ASSIGNMENT_SERVICE to SabUuids.ASSIGNMENT_CONTROL,
                SabUuids.DATA_SERVICE to SabUuids.DATA_CONTROL_POINT,
                SabUuids.DATA_SERVICE to SabUuids.FUNCTION_SOURCE,
            )

        /** Enabled in RoadSync's order (§4.2 steps 10, 12, 13). */
        val NOTIFIED =
            listOf(
                SabUuids.ASSIGNMENT_SERVICE to SabUuids.ASSIGNMENT_CONTROL,
                SabUuids.DATA_SERVICE to SabUuids.FUNCTION_SOURCE,
                SabUuids.DATA_SERVICE to SabUuids.DATA_CONTROL_POINT,
            )
    }
}

/** Runs [block] within [millis]; a timeout becomes [LinkError.TimedOut] for [op]. */
internal suspend fun <T> timed(
    op: GattOp,
    millis: Long,
    block: suspend () -> T,
): T =
    try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        throw LinkException(LinkError.TimedOut(op), e)
    }
