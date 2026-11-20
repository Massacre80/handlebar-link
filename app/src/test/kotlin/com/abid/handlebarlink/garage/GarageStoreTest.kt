package com.abid.handlebarlink.garage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Garage's file: what is written reads back, and older or newer files still read. */
class GarageStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val full =
        GarageData(
            odometer = listOf(OdometerReading(15_860, 1_795_000_000_000)),
            log = listOf(ServiceEntry(1, 20_700, 14_100, ServiceKind.GENERAL, "Wings Honda", 2_450)),
            service = ServicePlan(months = 6, km = 6_000, startDay = 20_700, startKm = 14_100),
            chain = ChainPlan(km = 1_000, startKm = 15_200),
            papers = mapOf(PaperKind.PUC to Paper(20_800, Lead.TWO_WEEKS)),
            custom = listOf(CustomReminder(2, "Tyre pressure", dueKm = 16_000)),
            colourway = "MATTE_GREY",
            alerts = mapOf("paper:PUC" to AlertMark("2026-12-10/-", AlertStage.SOON)),
            nextId = 3,
        )

    private suspend fun roundTrip(data: GarageData): GarageData {
        val out = ByteArrayOutputStream()
        GarageSerializer.writeTo(data, out)
        return GarageSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `everything written reads back the same`() =
        runTest {
            assertThat(roundTrip(full)).isEqualTo(full)
        }

    @Test
    fun `a file without the newer fields reads with their defaults, unknown fields are skipped`() =
        runTest {
            val old = """{"log":[{"id":1,"day":20700,"km":14100,"kind":"OIL"}],"future":{"x":1}}"""

            val data = GarageSerializer.readFrom(ByteArrayInputStream(old.encodeToByteArray()))

            assertThat(data.log.single().kind).isEqualTo(ServiceKind.OIL)
            assertThat(data.service).isEqualTo(ServicePlan())
            assertThat(data.nextId).isEqualTo(1L)
        }

    @Test
    fun `a file that is not Garage's is reported as corrupt`() =
        runTest {
            assertFailure { GarageSerializer.readFrom(ByteArrayInputStream("not json".encodeToByteArray())) }
                .isInstanceOf(CorruptionException::class)
        }

    @Test
    fun `the store applies a change atomically and keeps it`() =
        runTest {
            val store =
                DataStoreGarageStore(
                    DataStoreFactory.create(GarageSerializer, scope = backgroundScope) {
                        folder.newFile("garage.json").also { it.delete() }
                    },
                )

            store.update { it.enterOdometer(100, 1) }
            store.update { it.enterOdometer(200, 2) }

            assertThat(
                store.data
                    .first()
                    .latestReading
                    ?.km,
            ).isEqualTo(200)
        }
}
