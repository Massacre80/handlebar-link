package com.abid.handlebarlink.garage

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** When things come due: by date, by km, whichever first, and in what order (docs/PLAN.md "Garage"). */
class RemindersTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)
    private val now = clock.millis()

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    private fun reading(km: Int) = OdometerReading(km, now)

    private fun plan(
        startDay: String,
        startKm: Int,
    ) = ServicePlan(months = 6, km = 6_000, startDay = day(startDay), startKm = startKm)

    @Test
    fun `the service is due six months or 6,000 km after the last general service`() {
        val due = serviceDue(plan("2026-09-02", 14_100), today, reading(15_860))!!

        // 2 Sep + 6 months = 2 Mar 2027, 102 days from 20 Nov; 14,100 + 6,000 - 15,860 = 4,240 km.
        assertThat(due.dueDay).isEqualTo(LocalDate.parse("2027-03-02"))
        assertThat(due.daysLeft).isEqualTo(102L)
        assertThat(due.dueKm).isEqualTo(20_100)
        assertThat(due.kmLeft).isEqualTo(4_240)
        assertThat(due.state).isEqualTo(DueState.OK)
    }

    @Test
    fun `whichever comes first, the km makes the service due soon though the date is far`() {
        // 100 days left, but only 400 km (soon at 500 km).
        val due = serviceDue(plan("2026-08-31", 10_000), today, reading(15_600))!!

        assertThat(due.daysLeft).isEqualTo(100L)
        assertThat(due.state).isEqualTo(DueState.SOON)
    }

    @Test
    fun `whichever comes first, the date makes the service overdue though km are left`() {
        val due = serviceDue(plan("2026-05-19", 15_000), today, reading(15_100))!!

        assertThat(due.daysLeft).isEqualTo(-1L)
        assertThat(due.kmLeft).isEqualTo(5_900)
        assertThat(due.state).isEqualTo(DueState.OVERDUE)
    }

    @Test
    fun `the soon threshold is inclusive, the overdue one starts past the day`() {
        // Due in exactly 14 days: soon. Due today: soon, not overdue. Due in 15 days: OK.
        assertThat(serviceDue(plan("2026-06-04", 0), today, null)!!.state).isEqualTo(DueState.SOON)
        assertThat(serviceDue(plan("2026-05-20", 0), today, null)!!.state).isEqualTo(DueState.SOON)
        assertThat(serviceDue(plan("2026-06-05", 0), today, null)!!.state).isEqualTo(DueState.OK)
    }

    @Test
    fun `by km, the reading at the due km is soon and one past it is overdue`() {
        val plan = plan("2026-11-01", 10_000)

        assertThat(serviceDue(plan, today, reading(15_500))!!.state).isEqualTo(DueState.SOON)
        assertThat(serviceDue(plan, today, reading(15_499))!!.state).isEqualTo(DueState.OK)
        assertThat(serviceDue(plan, today, reading(16_000))!!.state).isEqualTo(DueState.SOON)
        assertThat(serviceDue(plan, today, reading(16_001))!!.state).isEqualTo(DueState.OVERDUE)
    }

    @Test
    fun `without a reading the km cannot make anything due`() {
        val due = serviceDue(plan("2026-11-01", 10_000), today, null)!!

        assertThat(due.kmLeft).isNull()
        assertThat(due.state).isEqualTo(DueState.OK)
    }

    @Test
    fun `no service is counted before a general service is logged`() {
        assertThat(serviceDue(ServicePlan(), today, reading(1_000))).isNull()
    }

    @Test
    fun `the gauge shows the larger share used, by time or by km`() {
        // 79 of 181 days (44 %) but 5,400 of 6,000 km (90 %).
        val due = serviceDue(plan("2026-09-02", 10_000), today, reading(15_400))!!

        assertThat(due.used).isEqualTo(0.9f)
    }

    @Test
    fun `a paper is due soon its lead ahead and overdue the day after it runs out`() {
        fun state(
            expiry: String,
            lead: Lead,
        ) = paperDue(PaperKind.PUC, Paper(day(expiry), lead), today).state

        assertThat(state("2026-12-20", Lead.MONTH)).isEqualTo(DueState.SOON)
        assertThat(state("2026-12-21", Lead.MONTH)).isEqualTo(DueState.OK)
        assertThat(state("2026-11-27", Lead.WEEK)).isEqualTo(DueState.SOON)
        assertThat(state("2026-11-28", Lead.WEEK)).isEqualTo(DueState.OK)
        assertThat(state("2026-11-20", Lead.WEEK)).isEqualTo(DueState.SOON)
        assertThat(state("2026-11-19", Lead.WEEK)).isEqualTo(DueState.OVERDUE)
        assertThat(paperDue(PaperKind.RC, null, today).state).isEqualTo(DueState.UNSET)
    }

    @Test
    fun `the chain counts its interval from when it was last done`() {
        val due = chainDue(ChainPlan(km = 1_000, startKm = 15_200), reading(15_860))

        assertThat(due.dueKm).isEqualTo(16_200)
        assertThat(due.kmLeft).isEqualTo(340)
        assertThat(due.state).isEqualTo(DueState.OK)
        assertThat(chainDue(ChainPlan(km = 1_000, startKm = 15_200), reading(16_100)).state).isEqualTo(DueState.SOON)
        assertThat(chainDue(ChainPlan(), reading(16_100)).state).isEqualTo(DueState.UNSET)
    }

    @Test
    fun `soonest first, overdue before soon before the rest, papers not added last`() {
        val data =
            GarageData(
                odometer = listOf(reading(15_860)),
                chain = ChainPlan(km = 1_000, startKm = 15_200),
                papers =
                    mapOf(
                        PaperKind.INSURANCE to Paper(day("2027-03-14")),
                        PaperKind.PUC to Paper(day("2026-11-16"), Lead.TWO_WEEKS),
                    ),
                custom = listOf(CustomReminder(1, "Tyre pressure", dueDay = day("2026-11-25"))),
            )

        val order = garageStatus(data, today).upcoming.map { it.id }

        assertThat(order).containsExactly(
            paperId(PaperKind.PUC),
            customId(1),
            CHAIN_ID,
            paperId(PaperKind.INSURANCE),
            paperId(PaperKind.RC),
        )
    }

    @Test
    fun `with a riding rate a km reminder is placed against the dates by its days`() {
        // 30 km a day over ten days; the chain's 340 km is about 11 days, the insurance (lead a week) 9 days away.
        val data =
            GarageData(
                odometer =
                    listOf(
                        OdometerReading(15_560, Instant.parse("2026-11-10T09:00:00Z").toEpochMilli()),
                        reading(15_860),
                    ),
                chain = ChainPlan(km = 1_000, startKm = 15_200, soonKm = 400),
                papers = mapOf(PaperKind.INSURANCE to Paper(day("2026-11-29"), Lead.TWO_WEEKS)),
            )

        val status = garageStatus(data, today)

        assertThat(status.kmPerDay).isEqualTo(30.0)
        assertThat(status.upcoming.take(2).map { it.id }).containsExactly(paperId(PaperKind.INSURANCE), CHAIN_ID)
    }

    @Test
    fun `without a riding rate a km reminder goes first in its group`() {
        val data =
            GarageData(
                odometer = listOf(reading(15_860)),
                chain = ChainPlan(km = 1_000, startKm = 15_200, soonKm = 400),
                papers = mapOf(PaperKind.INSURANCE to Paper(day("2026-11-21"), Lead.TWO_WEEKS)),
            )

        val status = garageStatus(data, today)

        assertThat(status.kmPerDay).isNull()
        assertThat(status.upcoming.take(2).map { it.id }).containsExactly(CHAIN_ID, paperId(PaperKind.INSURANCE))
    }

    @Test
    fun `the riding rate needs readings at least a day apart and the meter going up`() {
        val start = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()

        assertThat(kmPerDay(listOf(OdometerReading(100, start), OdometerReading(150, start + HALF_DAY)))).isNull()
        assertThat(kmPerDay(listOf(OdometerReading(150, start), OdometerReading(150, start + 2 * DAY)))).isNull()
        assertThat(kmPerDay(listOf(OdometerReading(100, start), OdometerReading(300, start + 2 * DAY))))
            .isEqualTo(100.0)
    }

    @Test
    fun `the latest reading is the newest by time, not the highest`() {
        val data =
            GarageData(
                odometer =
                    listOf(
                        reading(15_860),
                        OdometerReading(14_100, Instant.parse("2026-09-02T00:00:00Z").toEpochMilli()),
                    ),
            )

        assertThat(data.latestReading).isNotNull().transform { it.km }.isEqualTo(15_860)
    }

    @Test
    fun `days since a reading count local days`() {
        val zone = ZoneOffset.ofHoursMinutes(5, 30)
        // 18 Nov 22:00 UTC is 19 Nov 03:30 in India: one day before the 20th there.
        val reading = OdometerReading(1, Instant.parse("2026-11-18T22:00:00Z").toEpochMilli())

        assertThat(daysSince(reading, today, zone)).isEqualTo(1L)
        assertThat(daysSince(reading, today, ZoneOffset.UTC)).isEqualTo(2L)
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1_000L
        const val HALF_DAY = DAY / 2
    }
}
