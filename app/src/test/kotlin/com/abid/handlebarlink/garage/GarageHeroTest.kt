package com.abid.handlebarlink.garage

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.prop
import com.abid.handlebarlink.data.SelectedBike
import com.abid.handlebarlink.ui.garage.garageUiState
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The hero says the state of everything but the service, whose gauge shows it (owner: status once). */
class GarageHeroTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.now(clock)

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    private fun hero(data: GarageData) = garageUiState(data, SelectedBike("A", "HONDA"), today, ZoneOffset.UTC).hero

    private val onTrack =
        GarageData(
            service = ServicePlan(startDay = day("2026-09-02"), startKm = 14_100),
            papers = mapOf(PaperKind.INSURANCE to Paper(day("2027-03-14"))),
        )

    @Test
    fun `nothing added is the empty hero, even with an odometer reading or a colourway`() {
        assertThat(hero(GarageData(odometer = listOf(OdometerReading(1, clock.millis())), colourway = "MATTE_GREY")))
            .isEqualTo(GarageHero.Empty)
    }

    @Test
    fun `all on track says all good`() {
        assertThat(hero(onTrack)).isEqualTo(GarageHero.AllGood)
    }

    @Test
    fun `only the service due leaves the tag to the gauge`() {
        val soon = onTrack.copy(service = ServicePlan(startDay = day("2026-05-29"), startKm = 10_470))

        assertThat(hero(soon)).isEqualTo(GarageHero.ServiceOnly)
    }

    @Test
    fun `an overdue paper comes before a service due soon`() {
        val data =
            onTrack.copy(
                service = ServicePlan(startDay = day("2026-05-29"), startKm = 10_470),
                papers = onTrack.papers + (PaperKind.PUC to Paper(day("2026-11-16"))),
            )

        assertThat(hero(data))
            .isInstanceOf(GarageHero.Attention::class)
            .prop(GarageHero.Attention::due)
            .transform { it.id }
            .isEqualTo(paperId(PaperKind.PUC))
    }

    @Test
    fun `the readings' age is counted in the phone's days`() {
        val reading = OdometerReading(15_860, Instant.parse("2026-11-18T10:00:00Z").toEpochMilli())

        val state = garageUiState(GarageData(odometer = listOf(reading)), null, today, ZoneOffset.UTC)

        assertThat(state.readingDaysAgo).isEqualTo(2L)
    }
}
