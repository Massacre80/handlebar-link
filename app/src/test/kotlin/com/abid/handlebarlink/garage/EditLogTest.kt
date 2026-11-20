package com.abid.handlebarlink.garage

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Changing or deleting a log entry: its reading and the counts it started follow. */
class EditLogTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneOffset.UTC
    private val now = clock.millis()

    private fun day(text: String) = LocalDate.parse(text)

    private fun GarageData.log(
        kind: ServiceKind,
        day: String,
        km: Int,
        restart: Boolean = true,
    ) = logService(kind, day(day), km, restart, now, zone)

    private fun input(
        day: String,
        km: Int,
        kind: ServiceKind = ServiceKind.GENERAL,
        restart: Boolean = true,
    ) = ServiceInput(kind, day(day), km, workshop = null, cost = null, restart = restart)

    /** Two general services: May at 9,000 km, then September at 14,100 km (the start). */
    private val twoServices =
        GarageData()
            .log(ServiceKind.GENERAL, "2026-05-20", 9_000)
            .log(ServiceKind.GENERAL, "2026-09-02", 14_100)

    @Test
    fun `deleting the service that started the count hands it to the one before`() {
        val deleted = twoServices.deleteEntry(id = 2)

        assertThat(deleted.service.startDay).isEqualTo(day("2026-05-20").toEpochDay())
        assertThat(deleted.service.startKm).isEqualTo(9_000)
        assertThat(deleted.log.map { it.id }).containsExactly(1L)
    }

    @Test
    fun `deleting the only service leaves the count not started`() {
        val one = GarageData().log(ServiceKind.GENERAL, "2026-09-02", 14_100)

        val deleted = one.deleteEntry(id = 1)

        assertThat(deleted.service.startDay).isNull()
        assertThat(deleted.service.startKm).isNull()
    }

    @Test
    fun `deleting an entry removes the reading it carried and keeps the others`() {
        val withReading = twoServices.enterOdometer(15_000, now)

        val deleted = withReading.deleteEntry(id = 2)

        assertThat(deleted.odometer.map { it.km }).containsExactly(9_000, 15_000)
    }

    @Test
    fun `a typo in the start's km is corrected in the count and the reading`() {
        val edited = twoServices.editEntry(2, input("2026-09-02", 14_010), now, zone)

        assertThat(edited.service.startKm).isEqualTo(14_010)
        assertThat(edited.odometer.map { it.km }).containsExactly(9_000, 14_010)
    }

    @Test
    fun `turning the restart off on the start hands the count back`() {
        val edited = twoServices.editEntry(2, input("2026-09-02", 14_100, restart = false), now, zone)

        assertThat(edited.service.startKm).isEqualTo(9_000)
    }

    @Test
    fun `an entry from before the restart mark existed is still known as the start`() {
        // As written by the first Garage version: no restarted marks, no reading links.
        val old =
            twoServices.copy(
                log = twoServices.log.map { it.copy(restarted = false) },
                odometer = twoServices.odometer.map { it.copy(entry = null) },
            )

        val deleted = old.deleteEntry(id = 2)

        assertThat(deleted.service.startKm).isEqualTo(9_000)
    }

    @Test
    fun `deleting the chain entry that started the chain's count hands it to the one before`() {
        val chains =
            GarageData()
                .log(ServiceKind.CHAIN, "2026-06-01", 10_000)
                .log(ServiceKind.CHAIN, "2026-10-01", 13_000)

        assertThat(chains.deleteEntry(id = 2).chain.startKm).isEqualTo(10_000)
        assertThat(chains.deleteEntry(id = 1).chain.startKm).isEqualTo(13_000)
    }

    @Test
    fun `a chain start from Done it now is kept when a chain entry goes`() {
        val chains = GarageData().log(ServiceKind.CHAIN, "2026-06-01", 10_000).chainDone(km = 12_500)

        assertThat(chains.deleteEntry(id = 1).chain.startKm).isEqualTo(12_500)
    }
}
