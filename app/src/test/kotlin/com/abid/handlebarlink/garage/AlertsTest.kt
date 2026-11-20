package com.abid.handlebarlink.garage

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** What the daily check notifies: once per stage per due target, and again for a new target. */
class AlertsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)

    private fun puc(
        expiry: String,
        lead: Lead = Lead.MONTH,
        on: LocalDate = today,
    ) = paperDue(PaperKind.PUC, Paper(LocalDate.parse(expiry).toEpochDay(), lead), on)

    private fun stages(plan: AlertPlan) = plan.notify.map { (due, stage) -> due.id to stage }

    @Test
    fun `stages - due soon, a week before for a long lead, overdue, nothing when OK`() {
        assertThat(alertStage(puc("2026-12-10"))).isEqualTo(AlertStage.SOON)
        assertThat(alertStage(puc("2026-11-27"))).isEqualTo(AlertStage.WEEK)
        assertThat(alertStage(puc("2026-11-28"))).isEqualTo(AlertStage.SOON)
        assertThat(alertStage(puc("2026-11-27", Lead.WEEK))).isEqualTo(AlertStage.SOON)
        assertThat(alertStage(puc("2026-11-19"))).isEqualTo(AlertStage.OVERDUE)
        assertThat(alertStage(puc("2027-01-01"))).isNull()
        assertThat(alertStage(paperDue(PaperKind.RC, null, today))).isNull()
    }

    @Test
    fun `a reminder that comes due is notified once, not again the next day`() {
        val first = planAlerts(listOf(puc("2026-12-10")), emptyMap())
        val next = planAlerts(listOf(puc("2026-12-10", on = today.plusDays(1))), first.marks)

        assertThat(stages(first)).containsExactly(paperId(PaperKind.PUC) to AlertStage.SOON)
        assertThat(next.notify).isEmpty()
    }

    @Test
    fun `each later stage is notified once more`() {
        val soon = planAlerts(listOf(puc("2026-12-10")), emptyMap())
        val week = planAlerts(listOf(puc("2026-12-10", on = LocalDate.parse("2026-12-03"))), soon.marks)
        val overdue = planAlerts(listOf(puc("2026-12-10", on = LocalDate.parse("2026-12-11"))), week.marks)
        val again = planAlerts(listOf(puc("2026-12-10", on = LocalDate.parse("2026-12-12"))), overdue.marks)

        assertThat(stages(week)).containsExactly(paperId(PaperKind.PUC) to AlertStage.WEEK)
        assertThat(stages(overdue)).containsExactly(paperId(PaperKind.PUC) to AlertStage.OVERDUE)
        assertThat(again.notify).isEmpty()
    }

    @Test
    fun `a check that skipped a stage notifies only the latest`() {
        val plan = planAlerts(listOf(puc("2026-11-19")), emptyMap())

        assertThat(stages(plan)).containsExactly(paperId(PaperKind.PUC) to AlertStage.OVERDUE)
    }

    @Test
    fun `a renewed paper is a new target and is notified again when it comes due`() {
        val overdue = planAlerts(listOf(puc("2026-11-19")), emptyMap())
        val renewed = planAlerts(listOf(puc("2026-11-30")), overdue.marks)

        assertThat(stages(renewed)).containsExactly(paperId(PaperKind.PUC) to AlertStage.SOON)
    }

    @Test
    fun `a reminder back to OK drops its mark, so it is notified the next time`() {
        val soon = planAlerts(listOf(puc("2026-12-10")), emptyMap())
        val ok = planAlerts(listOf(puc("2027-12-10")), soon.marks)

        assertThat(ok.marks).isEqualTo(emptyMap())
    }

    @Test
    fun `a service logged restarts its target, so the next due is notified`() {
        val plan = ServicePlan(startDay = LocalDate.parse("2026-05-25").toEpochDay(), startKm = 0)
        val due = serviceDue(plan, today, null)!!
        val marks = planAlerts(listOf(due), emptyMap()).marks
        val later = serviceDue(plan.copy(startDay = LocalDate.parse("2026-05-26").toEpochDay()), today, null)!!

        assertThat(stages(planAlerts(listOf(later), marks))).containsExactly(SERVICE_ID to AlertStage.SOON)
    }
}
