package com.abid.handlebarlink.garage

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The rider's changes: logging a service restarts the count, readings, papers and own reminders. */
class GarageEditsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC)
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.now(clock)
    private val now = clock.millis()

    private fun GarageData.log(
        kind: ServiceKind,
        day: LocalDate = today,
        km: Int = 15_860,
        restart: Boolean = true,
    ) = logService(kind, day, km, restart, now, zone)

    @Test
    fun `logging a general service restarts the service count from it`() {
        val data =
            GarageData(service = ServicePlan(startDay = LocalDate.parse("2026-05-20").toEpochDay(), startKm = 9_000))

        val logged = data.log(ServiceKind.GENERAL)

        assertThat(logged.service.startDay).isEqualTo(today.toEpochDay())
        assertThat(logged.service.startKm).isEqualTo(15_860)
        // And the next service moves to 20 May 2027 or 21,860 km (Paper "Garage 5": "Next due 20 May or at 21,860 km").
        val next = serviceDue(logged.service, today, logged.latestReading)!!
        assertThat(next.dueDay).isEqualTo(LocalDate.parse("2027-05-20"))
        assertThat(next.dueKm).isEqualTo(21_860)
    }

    @Test
    fun `with the switch off the count stays where it was`() {
        val plan = ServicePlan(startDay = LocalDate.parse("2026-05-20").toEpochDay(), startKm = 9_000)

        val logged = GarageData(service = plan).log(ServiceKind.GENERAL, restart = false)

        assertThat(logged.service).isEqualTo(plan)
        assertThat(logged.log.single().kind).isEqualTo(ServiceKind.GENERAL)
    }

    @Test
    fun `other work never restarts the service count`() {
        val logged = GarageData().log(ServiceKind.OIL)

        assertThat(logged.service.startDay).isNull()
    }

    @Test
    fun `an older general service is logged without moving the count back`() {
        val start = LocalDate.parse("2026-09-02")
        val data = GarageData(service = ServicePlan(startDay = start.toEpochDay(), startKm = 14_100))

        val logged = data.log(ServiceKind.GENERAL, day = LocalDate.parse("2026-03-02"), km = 8_150)

        assertThat(logged.service.startDay).isEqualTo(start.toEpochDay())
        assertThat(logged.log.single().km).isEqualTo(8_150)
    }

    @Test
    fun `a general service on the same day as the current start restarts it`() {
        val data = GarageData(service = ServicePlan(startDay = today.toEpochDay(), startKm = 15_000))

        assertThat(data.log(ServiceKind.GENERAL, km = 15_010).service.startKm).isEqualTo(15_010)
    }

    @Test
    fun `chain and sprockets restart the chain count`() {
        val logged = GarageData(chain = ChainPlan(startKm = 12_000)).log(ServiceKind.CHAIN, km = 13_420)

        assertThat(logged.chain.startKm).isEqualTo(13_420)
    }

    @Test
    fun `each entry gets its own id and carries its odometer reading`() {
        val logged = GarageData().log(ServiceKind.OIL, km = 100).log(ServiceKind.TYRES, km = 200)

        assertThat(logged.log.map { it.id }).containsExactly(1L, 2L)
        assertThat(logged.nextId).isEqualTo(3L)
        assertThat(logged.latestReading?.km).isEqualTo(200)
    }

    @Test
    fun `a past entry's reading is dated that day, so it never replaces a newer reading`() {
        val data = GarageData().enterOdometer(15_860, now)

        val logged = data.log(ServiceKind.GENERAL, day = LocalDate.parse("2026-09-02"), km = 14_100)

        assertThat(logged.latestReading?.km).isEqualTo(15_860)
        assertThat(readingTime(LocalDate.parse("2026-09-02"), now, zone))
            .isEqualTo(Instant.parse("2026-09-02T00:00:00Z").toEpochMilli())
        assertThat(readingTime(today, now, zone)).isEqualTo(now)
    }

    @Test
    fun `an empty workshop is stored as none`() {
        val logged = GarageData().logService(ServiceKind.OIL, today, 1, false, now, zone, workshop = "  ")

        assertThat(logged.log.single().workshop).isNull()
    }

    @Test
    fun `the chain marked done starts at the latest reading`() {
        val data = GarageData().enterOdometer(15_200, now)

        assertThat(data.chainDone().chain.startKm).isEqualTo(15_200)
        assertThat(GarageData().chainDone()).isEqualTo(GarageData())
    }

    @Test
    fun `own reminders are by date or by km and are removed with their alert mark`() {
        val added = GarageData().addCustom(" Tyre pressure ", today.plusDays(3), null)
        val id = added.custom.single().id
        val marked = added.copy(alerts = mapOf(customId(id) to AlertMark("x", AlertStage.SOON)))

        assertThat(added.custom.single().name).isEqualTo("Tyre pressure")
        assertThat(
            marked
                .updateCustom(id, "Tyres", null, 20_000)
                .custom
                .single()
                .dueKm,
        ).isEqualTo(20_000)
        assertThat(marked.removeCustom(id).custom.isEmpty()).isTrue()
        assertThat(marked.removeCustom(id).alerts.isEmpty()).isTrue()
        assertFailure { GarageData().addCustom("Both", today, 1) }
    }

    @Test
    fun `a paper keeps its date and lead`() {
        val data = GarageData().setPaper(PaperKind.INSURANCE, LocalDate.parse("2027-03-14"), Lead.TWO_WEEKS)

        assertThat(
            data.papers[PaperKind.INSURANCE],
        ).isEqualTo(Paper(LocalDate.parse("2027-03-14").toEpochDay(), Lead.TWO_WEEKS))
    }
}
